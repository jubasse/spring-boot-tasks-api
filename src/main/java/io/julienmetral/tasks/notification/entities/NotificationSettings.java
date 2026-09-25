package io.julienmetral.tasks.notification.entities;

import io.julienmetral.tasks.identity.entities.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

/** Email notification preferences of one user. Users without a row get {@link #defaults(User)}. */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSettings {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            foreignKey = @ForeignKey(name = "notification_settings_userFK")
    )
    private User user;

    @ColumnDefault("true")
    @Column(name = "task_assigned", nullable = false)
    private boolean taskAssigned = true;

    @ColumnDefault("true")
    @Column(name = "task_unassigned", nullable = false)
    private boolean taskUnassigned = true;

    @ColumnDefault("true")
    @Column(name = "task_cancelled", nullable = false)
    private boolean taskCancelled = true;

    @ColumnDefault("true")
    @Column(name = "task_deleted", nullable = false)
    private boolean taskDeleted = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Every notification enabled; not persisted until the user changes something. */
    public static NotificationSettings defaults(User user) {
        NotificationSettings settings = new NotificationSettings();

        settings.setUser(user);
        settings.setUserId(user.getId());

        return settings;
    }
}
