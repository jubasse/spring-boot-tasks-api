package io.julienmetral.tasks.identity.mail;

import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Writes the verification email; {@link MailService} sends it once the sign-up transaction commits. */
@Component
@RequiredArgsConstructor
public class VerificationEmailSender {

    private final MailService mailService;
    private final EmailVerificationProperties properties;

    @EventListener
    public void send(EmailVerificationRequested event) {
        mailService.send(new MailMessage(
                event.email(),
                "Verify your email address",
                """
                        Hello %s,

                        Please confirm your email address by opening this link:
                        %s?token=%s

                        The link expires at %s.

                        If you did not create an account, you can ignore this email.
                        """.formatted(
                        event.displayName(),
                        properties.verifyUrl(),
                        event.token(),
                        event.expiresAt()
                )
        ));
    }
}
