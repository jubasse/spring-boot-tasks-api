package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-03-10T08:00:00Z");

    private static final int BATCH_SIZE = 3;

    private static final OutboxProperties PROPERTIES = new OutboxProperties(
            Duration.ofSeconds(5),
            BATCH_SIZE,
            Duration.ofMinutes(10),
            Duration.ofSeconds(7),
            Duration.ofDays(7),
            "0 0 * * * *"
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private OutboxMessageRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitOperations channelOperations;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, rabbitTemplate, jsonMapper, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishNowSendsTheLockedRowsAsJsonWithTheirTypeHeaderToTheirQueue() {
        OutboxMessage row = row("mail.send", "io.julienmetral.tasks.mail.MailMessage",
                Map.of("to", "jane@example.com", "subject", "Subject", "text", "Body"));
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerRunsTheCallbackOnAChannel();

        relay.publishNow(List.of(row.getId()));

        Message sent = sentMessage("mail.send");
        MessageProperties properties = sent.getMessageProperties();
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getMessageId()).isEqualTo(row.getId().toString());
        assertThat(properties.<String>getHeader("__TypeId__")).isEqualTo("io.julienmetral.tasks.mail.MailMessage");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        JsonNode body = jsonMapper.readTree(sent.getBody());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("to").asString()).isEqualTo("jane@example.com");
        assertThat(body.path("subject").asString()).isEqualTo("Subject");
        assertThat(body.path("text").asString()).isEqualTo("Body");
    }

    @Test
    void publishNowWaitsForTheBrokerConfirmAfterSending() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerRunsTheCallbackOnAChannel();

        relay.publishNow(List.of(row.getId()));

        var order = inOrder(channelOperations);
        order.verify(channelOperations).send(eq(""), eq("mail.send"), any(Message.class));
        order.verify(channelOperations).waitForConfirmsOrDie(7_000);
    }

    @Test
    void confirmedRowIsMarkedPublishedNow() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerRunsTheCallbackOnAChannel();

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void publishNowWithNothingLeftToPublishNeverTouchesTheBroker() {
        UUID alreadyPublished = UUID.randomUUID();
        when(repository.lockUnpublished(List.of(alreadyPublished))).thenReturn(List.of());

        relay.publishNow(List.of(alreadyPublished));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unreachableBrokerLeavesTheRowUnpublishedAndSchedulesARetryInFiveSeconds() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerIsUnreachable();

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("java.net.ConnectException: Connection refused");
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void unconfirmedMessageIsNotMarkedPublished() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerRunsTheCallbackOnAChannel();
        doThrow(new AmqpTimeoutException("Timed out waiting for confirms"))
                .when(channelOperations).waitForConfirmsOrDie(7_000);

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("Timed out waiting for confirms");
    }

    @Test
    void failedPublishIsLoggedWithTheRowTheQueueAndTheDelay(CapturedOutput output) {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerIsUnreachable();

        relay.publishNow(List.of(row.getId()));

        assertThat(output).contains(
                "Could not publish outbox message " + row.getId() + " to mail.send (attempt 1), retrying in PT5S",
                "Connection refused"
        );
    }

    @ParameterizedTest(name = "after {0} failed attempts, the next one waits {1}")
    @CsvSource({
            "0, PT5S",
            "1, PT10S",
            "2, PT20S",
            "3, PT40S",
            "6, PT5M20S",
            "7, PT10M",
            "40, PT10M"
    })
    void retryDelayDoublesFromFiveSecondsUpToTheMaximum(int previousAttempts, Duration expectedDelay) {
        OutboxMessage row = mailRow();
        row.setAttempts(previousAttempts);
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        brokerIsUnreachable();

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getAttempts()).isEqualTo(previousAttempts + 1);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(expectedDelay));
    }

    @Test
    void longErrorIsTruncatedToTheColumnLength() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        String error = "x".repeat(999) + "yz" + "overflow";
        doThrow(new AmqpConnectException(error, new ConnectException())).when(rabbitTemplate).invoke(any());

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getLastError()).hasSize(1_000).isEqualTo("x".repeat(999) + "y");
    }

    @Test
    void errorOfExactlyTheColumnLengthIsKeptWhole() {
        OutboxMessage row = mailRow();
        when(repository.lockUnpublished(List.of(row.getId()))).thenReturn(List.of(row));
        String error = "e".repeat(1_000);
        doThrow(new AmqpConnectException(error, new ConnectException())).when(rabbitTemplate).invoke(any());

        relay.publishNow(List.of(row.getId()));

        assertThat(row.getLastError()).isEqualTo(error);
    }

    @Test
    void oneFailedRowDoesNotStopTheOthers() {
        OutboxMessage first = mailRow();
        OutboxMessage failing = row("avatar.process", "io.julienmetral.tasks.identity.messaging.AvatarUploaded",
                Map.of("userId", UUID.randomUUID().toString(), "uploadId", UUID.randomUUID().toString()));
        OutboxMessage last = mailRow();
        when(repository.lockDue(NOW, BATCH_SIZE)).thenReturn(List.of(first, failing, last));
        brokerRunsTheCallbackOnAChannel();
        doAnswer(invocation -> {
            if ("avatar.process".equals(invocation.getArgument(1))) {
                throw new AmqpConnectException(new ConnectException("Connection reset"));
            }
            return null;
        }).when(channelOperations).send(anyString(), anyString(), any(Message.class));

        int published = relay.publishDue();

        assertThat(published).isEqualTo(2);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(last.getPublishedAt()).isEqualTo(NOW);
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(1);
        verify(channelOperations).send(eq(""), eq("mail.send"),
                argThat(message -> message.getMessageProperties().getMessageId().equals(last.getId().toString())));
    }

    @Test
    void publishDueLocksTheRowsDueNowUpToTheBatchSizeAndReturnsHowManyWerePublished() {
        List<OutboxMessage> due = List.of(mailRow(), mailRow(), mailRow());
        when(repository.lockDue(NOW, BATCH_SIZE)).thenReturn(due);
        brokerRunsTheCallbackOnAChannel();

        int published = relay.publishDue();

        assertThat(published).isEqualTo(3);
        assertThat(due).allSatisfy(row -> assertThat(row.getPublishedAt()).isEqualTo(NOW));
    }

    @Test
    void publishDueWithNothingDueReturnsZeroWithoutTouchingTheBroker() {
        when(repository.lockDue(NOW, BATCH_SIZE)).thenReturn(List.of());

        assertThat(relay.publishDue()).isZero();

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void publishDueCountsOnlyTheConfirmedRows() {
        when(repository.lockDue(NOW, BATCH_SIZE)).thenReturn(List.of(mailRow(), mailRow()));
        brokerIsUnreachable();

        assertThat(relay.publishDue()).isZero();
    }

    @Test
    void deletePublishedDeletesRowsPublishedBeforeTheRetentionAndReturnsTheCount() {
        when(repository.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(4);

        assertThat(relay.deletePublished()).isEqualTo(4);
    }

    private void brokerRunsTheCallbackOnAChannel() {
        when(rabbitTemplate.invoke(any())).thenAnswer(invocation ->
                invocation.<OperationsCallback<?>>getArgument(0).doInRabbit(channelOperations));
    }

    private void brokerIsUnreachable() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).invoke(any());
    }

    private Message sentMessage(String queue) {
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(channelOperations).send(eq(""), eq(queue), sent.capture());
        return sent.getValue();
    }

    private static OutboxMessage mailRow() {
        return row("mail.send", "io.julienmetral.tasks.mail.MailMessage",
                Map.of("to", UUID.randomUUID() + "@example.com", "subject", "Subject", "text", "Body"));
    }

    private static OutboxMessage row(String queue, String type, Map<String, Object> payload) {
        OutboxMessage row = new OutboxMessage();
        row.setId(UUID.randomUUID());
        row.setQueue(queue);
        row.setType(type);
        row.setPayload(payload);
        row.setCreatedAt(NOW.minusSeconds(1));
        row.setNextAttemptAt(NOW.minusSeconds(1));
        return row;
    }
}
