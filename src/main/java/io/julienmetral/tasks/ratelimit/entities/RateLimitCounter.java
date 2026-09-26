package io.julienmetral.tasks.ratelimit.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;

/**
 * Written only by {@code RateLimitQueries}, whose atomic upsert does the counting; the entity exists so that the
 * table stays in the Hibernate model.
 */
@Entity
@Immutable
@Table(name = "rate_limit_counters")
@Getter
@NoArgsConstructor
public class RateLimitCounter {

    @EmbeddedId
    private Id id;

    @Column(name = "count", nullable = false)
    private int count;

    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Column(name = "bucket_key", nullable = false, length = 400)
        private String bucketKey;

        @Column(name = "window_start", nullable = false)
        private Instant windowStart;
    }
}
