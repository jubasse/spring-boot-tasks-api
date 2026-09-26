package io.julienmetral.tasks.mail;

import io.julienmetral.tasks.messaging.services.Outbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for sending emails from any feature.
 * <p>
 * The email is written to the outbox in the caller's transaction (a transaction of its own when there is none):
 * nothing goes out for work that was rolled back, and a RabbitMQ outage only delays it. {@link MailQueueListener}
 * then sends it, so requests never wait for SMTP and a failed delivery is retried.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final Outbox outbox;

    @Transactional
    public void send(MailMessage message) {
        outbox.enqueue(MailQueues.SEND, message);
    }
}
