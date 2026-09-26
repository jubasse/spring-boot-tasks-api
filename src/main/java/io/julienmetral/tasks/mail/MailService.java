package io.julienmetral.tasks.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Entry point for sending emails from any feature.
 * <p>
 * Called inside a transaction, the email is queued only after it commits, so nothing goes out for work that was
 * rolled back. It is then sent by a RabbitMQ consumer ({@link MailDispatcher}, {@link MailQueueListener}), so
 * requests never wait for SMTP and a failed delivery is retried.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final ApplicationEventPublisher eventPublisher;

    public void send(MailMessage message) {
        eventPublisher.publishEvent(new MailRequested(message));
    }

    record MailRequested(MailMessage message) {
    }
}
