package io.julienmetral.tasks.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
class MailDispatcher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Queues the email for {@link MailQueueListener} once the publishing transaction commits, or immediately when
     * there is none ({@code fallbackExecution}). Runs on the async executor so that a slow or unreachable broker
     * (the template retries) never delays the response.
     * <p>
     * Warning: if the broker stays unreachable past the retries, the email is lost and only logged. The business
     * operation has already committed; a transactional outbox would be the next step if that ever matters.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void dispatch(MailService.MailRequested event) {
        MailMessage message = event.message();

        try {
            rabbitTemplate.convertAndSend(MailQueues.SEND, message);
        } catch (AmqpException exception) {
            log.error("Could not queue email \"{}\": it will not be sent", message.subject(), exception);
        }
    }
}
