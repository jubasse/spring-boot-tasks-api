package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.shared.entities.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "tasks",
        uniqueConstraints = {
            // Covers soft-deleted rows too: the reference of a deleted task stays taken (see
            // TaskRepository)
            @UniqueConstraint(name = "tasks_referenceUQ", columnNames = "reference")
        },
        indexes = {
            // The reminder job scans tasks by due date
            @Index(name = "tasks_due_atIDX", columnList = "due_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class Task extends AuditableEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reference", nullable = false, updatable = false, length = 30)
    private String reference;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TaskStatus status = TaskStatus.TO_DO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Column(name = "due_at")
    private @Nullable Instant dueAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private @Nullable String cancelledReason;

    @Column(name = "blocked_at")
    private @Nullable Instant blockedAt;

    @Column(name = "blocked_reason", length = 500)
    private @Nullable String blockedReason;

    @Version
    @Column(nullable = false)
    private long version;

    // UserProfile, not User: soft-deleted users must still load (see UserProfile)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", foreignKey = @ForeignKey(name = "tasks_created_byFK"))
    private @Nullable UserProfile createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id", foreignKey = @ForeignKey(name = "tasks_assigned_toFK"))
    private @Nullable UserProfile assignedTo;

    public @Nullable UUID currentAssigneeId() {
        return assignedTo == null ? null : assignedTo.getId();
    }
}
