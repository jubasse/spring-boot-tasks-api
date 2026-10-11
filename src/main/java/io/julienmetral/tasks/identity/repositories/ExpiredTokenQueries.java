package io.julienmetral.tasks.identity.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Native SQL: one set-based {@code DELETE} per table, where JPA would load every row first. Refresh tokens go by whole
 * family: {@code RefreshTokenService.rotate} detects reuse by finding the replayed token revoked, so a revoked token
 * must stay while a token of its family can still be used.
 */
@Repository
@RequiredArgsConstructor
public class ExpiredTokenQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** Deletes every token of a family none of whose tokens expires at or after {@code cutoff}. */
    public int deleteRefreshFamiliesExpiredBefore(Instant cutoff) {
        return jdbc.update(
                """
                        DELETE FROM refresh_tokens t
                        WHERE NOT EXISTS (
                            SELECT 1 FROM refresh_tokens f
                            WHERE f.family_id = t.family_id AND f.expires_at >= :cutoff
                        )
                        """,
                cutoff(cutoff)
        );
    }

    public int deleteVerificationTokensExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM email_verification_tokens WHERE expires_at < :cutoff", cutoff(cutoff));
    }

    public int deleteResetTokensExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM password_reset_tokens WHERE expires_at < :cutoff", cutoff(cutoff));
    }

    private static MapSqlParameterSource cutoff(Instant cutoff) {
        return new MapSqlParameterSource("cutoff", Timestamp.from(cutoff));
    }
}
