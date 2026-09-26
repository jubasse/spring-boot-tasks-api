package io.julienmetral.tasks.mail;

/** A plain-text email. The sender is set on sending, from {@link MailProperties}. */
public record MailMessage(
        String to,
        String subject,
        String text
) {
}
