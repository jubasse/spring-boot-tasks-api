package io.julienmetral.tasks.identity.mail;

import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Writes the password reset email; {@link MailService} sends it once the request transaction commits. */
@Component
@RequiredArgsConstructor
public class PasswordResetEmailSender {

    private final MailService mailService;
    private final PasswordResetProperties properties;

    @EventListener
    public void send(PasswordResetRequested event) {
        mailService.send(new MailMessage(
                event.email(),
                "Reset your password",
                """
                        Hello %s,

                        Someone asked to reset the password of your account. To choose a new password, open this link:
                        %s?token=%s

                        The link expires at %s and can be used once. Resetting your password signs you out everywhere.

                        If you did not ask for this, ignore this email: your password stays unchanged.
                        """.formatted(
                        event.displayName(),
                        properties.resetUrl(),
                        event.token(),
                        event.expiresAt()
                )
        ));
    }
}
