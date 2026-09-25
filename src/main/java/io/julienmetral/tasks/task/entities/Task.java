package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.shared.entities.AuditableEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "tasks",
    uniqueConstraints = {
            // Covers soft-deleted rows too: the reference of a deleted task stays taken (see TaskRepository)
            @UniqueConstraint(
                    name = "tasks_referenceUQ",
                    columnNames = "reference"
            )
    }
)
@Getter
@Setter
@NoArgsConstructor
public class Task extends AuditableEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(
            name = "reference",
            nullable = false,
            updatable = false,
            length = 30
    )
    private String reference;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TaskStatus status = TaskStatus.TO_DO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    // Optional business dates
    @Column(name = "due_at")
    private Instant dueAt; // Task deadline

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Version
    @Column(nullable = false)
    private long version;

    // Users can be soft-deleted (@SoftDelete). The association is then loaded as null (@NotFound(IGNORE), which also
    // makes it eager), so it is read-only: the id columns are the ones written, and a null association can never
    // erase who the task was assigned to or created by. Set users through setAssignedTo / setCreatedBy, which keep
    // both fields in sync.
    @ManyToOne(fetch = FetchType.EAGER)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
            name = "created_by_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "tasks_created_byFK")
    )
    private User createdBy;

    @Setter(AccessLevel.NONE)
    @Column(name = "created_by_id")
    private UUID createdById;

    @ManyToOne(fetch = FetchType.EAGER)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
            name = "assigned_to_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "tasks_assigned_toFK")
    )
    private User assignedTo;

    @Setter(AccessLevel.NONE)
    @Column(name = "assigned_to_id")
    private UUID assignedToId;

    public void setCreatedBy(User user) {
        this.createdBy = user;
        this.createdById = user == null ? null : user.getId();
    }

    public void setAssignedTo(User user) {
        this.assignedTo = user;
        this.assignedToId = user == null ? null : user.getId();
    }

    /** The assignee's id, also known when the assignee is soft-deleted and the association is null. */
    public UUID currentAssigneeId() {
        return assignedTo != null ? assignedTo.getId() : assignedToId;
    }
}
