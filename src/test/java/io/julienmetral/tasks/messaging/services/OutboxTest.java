package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailQueues;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxTest {

    private static final Instant NOW = Instant.parse("2026-03-10T08:00:00Z");

    private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final MailMessage MAIL = new MailMessage("jane@example.com", "Subject", "Body");

    @Mock
    private OutboxMessageRepository repository;

    @Mock
    private OutboxRelay relay;

    private Outbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new Outbox(repository, relay, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // Stands in for the transaction that Propagation.MANDATORY requires: enqueue registers its after-commit hook here
    @BeforeEach
    void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void enqueueSavesARowDueNowWithTheQueueTheClassNameAndThePayload() {
        OutboxMessage row = enqueueAndCaptureRow(MailQueues.SEND, MAIL);

        assertThat(row.getQueue()).isEqualTo("mail.send");
        assertThat(row.getType()).isEqualTo("io.julienmetral.tasks.mail.MailMessage");
        assertThat(row.getPayload()).isEqualTo(Map.of("to", "jane@example.com", "subject", "Subject", "text", "Body"));
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    void enqueueStoresUuidsOfThePayloadAsStrings() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID uploadId = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        OutboxMessage row = enqueueAndCaptureRow(AvatarQueues.PROCESS, new AvatarUploaded(userId, uploadId));

        assertThat(row.getQueue()).isEqualTo("avatar.process");
        assertThat(row.getType()).isEqualTo(AvatarUploaded.class.getName());
        assertThat(row.getPayload()).isEqualTo(Map.of("userId", userId.toString(), "uploadId", uploadId.toString()));
    }

    @Test
    void enqueuePublishesNothingBeforeTheTransactionCommits() {
        savingAssignsTheRowId();

        outbox.enqueue(MailQueues.SEND, MAIL);

        verifyNoInteractions(relay);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void commitPublishesTheSavedRowRightAway() {
        savingAssignsTheRowId();
        outbox.enqueue(MailQueues.SEND, MAIL);

        TransactionSynchronizationUtils.triggerBeforeCommit(false);
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(relay).publishNow(List.of(ROW_ID));
    }

    @Test
    void rollbackPublishesNothing() {
        savingAssignsTheRowId();
        outbox.enqueue(MailQueues.SEND, MAIL);

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(relay);
    }

    private OutboxMessage enqueueAndCaptureRow(String queue, Object message) {
        savingAssignsTheRowId();

        outbox.enqueue(queue, message);

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    // The database generates the id on insert
    private void savingAssignsTheRowId() {
        when(repository.save(any(OutboxMessage.class))).thenAnswer(invocation -> {
            OutboxMessage row = invocation.getArgument(0);
            row.setId(ROW_ID);
            return row;
        });
    }
}
