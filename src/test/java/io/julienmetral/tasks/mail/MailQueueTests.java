package io.julienmetral.tasks.mail;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the mail queue: {@link MailService} queues on RabbitMQ after commit, and
 * {@link MailQueueListener} sends to the Mailpit container.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class MailQueueTests {

    // Mail is queued asynchronously after commit: "nothing sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(800);

    @Autowired
    private MailService mailService;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void emailSentInCommittedTransactionReachesSmtpThroughTheQueue() {
        String email = uniqueEmail();

        inTransaction().executeWithoutResult(status ->
                mailService.send(new MailMessage(email, "Queued", "Sent through RabbitMQ")));

        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Sent through RabbitMQ");
        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void emailWaitsInTheQueueWhileNoConsumerRunsAndIsSentOnceOneStarts() throws InterruptedException {
        String email = uniqueEmail();

        listenerRegistry.stop();
        try {
            inTransaction().executeWithoutResult(status ->
                    mailService.send(new MailMessage(email, "Waiting", "Waited in the queue")));

            awaitQueuedFor(email);
            assertThat(mailpit.countTo(email)).isZero();
        } finally {
            listenerRegistry.start();
        }

        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Waited in the queue");
    }

    @Test
    void emailSentInRolledBackTransactionIsNeverQueued() throws InterruptedException {
        String email = uniqueEmail();

        inTransaction().executeWithoutResult(status -> {
            mailService.send(new MailMessage(email, "Rolled back", "Must not be sent"));
            status.setRollbackOnly();
        });
        inTransaction().executeWithoutResult(status ->
                mailService.send(new MailMessage(email, "Committed", "Sent after the rollback")));

        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Sent after the rollback");
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void sendQueueIsDurableAndDeadLettersToTheDeadLetterQueue() {
        JsonNode queue = managementApi("/api/queues/%2F/" + MailQueues.SEND);

        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("auto_delete").asBoolean()).isFalse();
        assertThat(queue.path("exclusive").asBoolean()).isFalse();
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").isString()).isTrue();
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").asString()).isEmpty();
        assertThat(queue.path("arguments").path("x-dead-letter-routing-key").asString())
                .isEqualTo(MailQueues.DEAD_LETTER);
    }

    @Test
    void deadLetterQueueIsDurableAndDeadLettersNowhere() {
        JsonNode queue = managementApi("/api/queues/%2F/" + MailQueues.DEAD_LETTER);

        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("auto_delete").asBoolean()).isFalse();
        assertThat(queue.path("arguments").has("x-dead-letter-exchange")).isFalse();
        assertThat(queue.path("arguments").has("x-dead-letter-routing-key")).isFalse();
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    // Peeks with ackmode ack_requeue_true: the messages go back to the queue for the consumer started afterwards
    private void awaitQueuedFor(String email) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));

        while (true) {
            JsonNode messages = managementApiPost(
                    "/api/queues/%2F/" + MailQueues.SEND + "/get",
                    Map.of("count", 100, "ackmode", "ack_requeue_true", "encoding", "auto")
            );

            for (JsonNode message : messages) {
                JsonNode payload = jsonMapper.readTree(message.path("payload").asString());

                if (email.equals(payload.path("to").asString())) {
                    return;
                }
            }

            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No message for " + email + " in " + MailQueues.SEND);
            }

            Thread.sleep(100);
        }
    }

    private JsonNode managementApi(String path) {
        String body = managementClient()
                .get()
                .uri(URI.create(rabbitContainer.getHttpUrl() + path))
                .retrieve()
                .body(String.class);

        return jsonMapper.readTree(body);
    }

    private JsonNode managementApiPost(String path, Object request) {
        String body = managementClient()
                .post()
                .uri(URI.create(rabbitContainer.getHttpUrl() + path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(request))
                .retrieve()
                .body(String.class);

        return jsonMapper.readTree(body);
    }

    private RestClient managementClient() {
        return RestClient.builder()
                .defaultHeaders(headers -> headers.setBasicAuth(
                        rabbitContainer.getAdminUsername(),
                        rabbitContainer.getAdminPassword()
                ))
                .build();
    }

    private static String uniqueEmail() {
        return "queue-" + UUID.randomUUID() + "@example.com";
    }
}
