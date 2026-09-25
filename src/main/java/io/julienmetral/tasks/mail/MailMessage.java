package io.julienmetral.tasks.mail;

/** A plain-text email. The sender is set by {@link MailService} from {@link MailProperties}. */
public record MailMessage(
        String to,
        String subject,
        String text
) {
}
