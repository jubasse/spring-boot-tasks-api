package io.julienmetral.tasks.support;

import io.julienmetral.tasks.mail.MailQueues;
import java.util.Objects;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counts the emails written to the outbox, to prove at once that a call sent no email, or no second
 * one, without waiting for Mailpit.
 *
 * <p>The emails of a request or a service call are written to {@code outbox_messages}
 * synchronously: their sender is a plain {@code @EventListener} that calls {@code
 * MailService.send}, which writes the row in the transaction of the call the test made. Once that
 * call has returned, its email is in the table or will never be; Mailpit, reached through RabbitMQ,
 * could only show it after a guessed delay. The count covers every email ever queued for the
 * address, delivered or not.
 *
 * <p>Warning: some emails are decided on another thread, after the test's call has returned: the
 * export emails (published by the export runner on its listener thread) and the email of a webhook
 * disabled after failed deliveries (sent by the delivery consumer). Their absence still needs a
 * wait.
 *
 * <p>Warning: it proves "not queued", which means "not sent" only while the senders stay
 * synchronous. An {@code @Async} sender or a {@code @TransactionalEventListener} would write the
 * row after the call returns, and this count would pass too early: {@code
 * MailSendersAreSynchronousTest} fails if one appears. Positive checks ("the email arrived, with
 * this text") stay on {@link Mailpit}, which also tests the delivery.
 */
@TestComponent
public class MailOutbox {

    // Addresses are compared without case, as Mailpit's search does: an email sent to the address
    // as typed, rather than as stored, must still count
    private static final String COUNT =
            """
            SELECT count(*) FROM outbox_messages
            WHERE queue = ? AND lower(payload ->> 'to') = lower(?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public MailOutbox(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Number of emails ever queued for this address. */
    public int countQueuedTo(String email) {
        return count(COUNT, MailQueues.SEND, email);
    }

    /** Number of emails ever queued for this address whose subject contains the given text. */
    public int countQueuedTo(String email, String subjectContains) {
        return count(
                COUNT + " AND strpos(payload ->> 'subject', ?) > 0",
                MailQueues.SEND,
                email,
                subjectContains);
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);

        return Objects.requireNonNull(count, "count(*) always returns a row");
    }
}
