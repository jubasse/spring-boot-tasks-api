package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.media.model.Media;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "task_attachments",
        uniqueConstraints = {
                @UniqueConstraint(name = "task_attachments_media_idUQ", columnNames = "media_id")
        },
        indexes = {
                @Index(name = "task_attachments_task_idIDX", columnList = "task_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TaskAttachment {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // EAGER: Hibernate refuses a LAZY to-one towards a @SoftDelete entity and fails at startup. Attachments of a
    // soft-deleted task therefore do not load through JPA; the purge of those files must use native queries.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "task_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "task_attachments_taskFK")
    )
    private Task task;

    // ManyToOne rather than OneToOne: the uniqueness is the named constraint above (see User.avatar)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "media_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "task_attachments_mediaFK")
    )
    private Media media;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
