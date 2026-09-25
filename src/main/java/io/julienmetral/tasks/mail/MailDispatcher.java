package io.julienmetral.tasks.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
class MailDispatcher {

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    /**
     * Runs after the publishing transaction commits, or immediately when there is none ({@code fallbackExecution}),
     * on the async executor. A delivery failure is logged: it must not break the business operation that triggered it.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void dispatch(MailService.MailRequested event) {
        MailMessage message = event.message();
        SimpleMailMessage mail = new SimpleMailMessage();

        mail.setFrom(properties.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());

        try {
            mailSender.send(mail);
        } catch (MailException exception) {
            log.warn("Could not send email \"{}\"", message.subject(), exception);
        }
    }
}
