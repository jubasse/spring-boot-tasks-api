package io.julienmetral.tasks.shared.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Written only by ShedLock (see {@code SchedulerLockConfiguration}); the entity keeps the table in the Hibernate model,
 * without which {@code liquibase:diff} proposes to drop it. Times are UTC wall-clock values from the database.
 * <p>
 * Warning: never delete a row to release a stuck lock. ShedLock remembers which rows exist and then only updates
 * them, so every later run would be skipped until a restart. Set {@code lock_until} to {@code now()} instead.
 */
@Entity
@Immutable
@Table(name = "scheduler_locks")
@Getter
@NoArgsConstructor
public class ScheduledJobLock {

    @Id
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "lock_until", nullable = false)
    private LocalDateTime lockUntil;

    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", nullable = false)
    private String lockedBy;
}
