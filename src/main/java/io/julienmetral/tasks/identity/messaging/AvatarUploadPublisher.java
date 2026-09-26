package io.julienmetral.tasks.identity.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
class AvatarUploadPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Queues the upload once its transaction commits, so the worker never looks for a row that is not visible yet.
     * <p>
     * Warning: if the broker stays unreachable past the template retries, the photo stays pending and only a new
     * upload replaces it. The upload itself is committed and kept.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publish(AvatarUploaded event) {
        try {
            rabbitTemplate.convertAndSend(AvatarQueues.PROCESS, event);
        } catch (AmqpException exception) {
            log.error("Could not queue the profile photo {} of user {}", event.uploadId(), event.userId(), exception);
        }
    }
}
