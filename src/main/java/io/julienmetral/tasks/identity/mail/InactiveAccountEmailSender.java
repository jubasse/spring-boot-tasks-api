package io.julienmetral.tasks.identity.mail;

import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Writes the inactivity warning; {@link MailService} sends it once the retention run commits. */
@Component
@RequiredArgsConstructor
public class InactiveAccountEmailSender {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final MailService mailService;

    @EventListener
    public void send(InactiveAccountWarned event) {
        mailService.send(new MailMessage(
                event.email(),
                "Your account will be deleted",
                """
                        Hello %s,

                        You have not used your account for a long time. To protect your personal data, it will be \
                        deleted on %s.

                        To keep it, simply log in before then.
                        """.formatted(
                        event.displayName(),
                        DATE.format(event.deletionAt())
                )
        ));
    }
}
