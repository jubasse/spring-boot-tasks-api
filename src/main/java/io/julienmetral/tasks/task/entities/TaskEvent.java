package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "task_events")
@NoArgsConstructor
public class TaskEvent {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(
            name = "task_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "task_events_taskFK")
    )
    private Task task;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(
            name = "actor_id",
            foreignKey = @ForeignKey(name = "task_events_actorFK")
    )
    private UserProfile actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TaskEventType type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;
}