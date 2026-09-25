package io.julienmetral.tasks.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Entry point for sending emails from any feature.
 * <p>
 * Called inside a transaction, the email is sent only after it commits, so nothing goes out for work that was
 * rolled back. Sending happens asynchronously (see {@link MailDispatcher}), so requests never wait for SMTP.
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
