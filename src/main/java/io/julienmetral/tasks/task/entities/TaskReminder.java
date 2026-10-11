package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;

/**
 * A reminder already sent. Rows are written only by {@code TaskReminderQueries}, whose insert
 * relies on the unique constraint to send each reminder once; the entity exists so that the schema
 * stays in the Hibernate model.
 */
@Entity
@Immutable
@Table(
        name = "task_reminders",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "task_reminders_task_id_kind_due_at_recipient_idUQ",
                    columnNames = {"task_id", "kind", "due_at", "recipient_id"})
        })
@Getter
@NoArgsConstructor
public class TaskReminder {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // EAGER: Hibernate refuses a LAZY to-one towards a @SoftDelete entity (see TaskAttachment.task)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "task_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "task_reminders_taskFK"))
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "recipient_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "task_reminders_recipientFK"))
    private UserProfile recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private TaskReminderKind kind;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}
