package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.config.ExpiredTokenPurgeProperties;
import io.julienmetral.tasks.identity.repositories.ExpiredTokenQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ExpiredTokenPurgeService {

    private final ExpiredTokenQueries queries;
    private final ExpiredTokenPurgeProperties properties;
    private final Clock clock;

    public record PurgedTokens(
            int refreshTokens,
            int verificationTokens,
            int resetTokens
    ) {

        public boolean isEmpty() {
            return refreshTokens == 0 && verificationTokens == 0 && resetTokens == 0;
        }
    }

    /** Deletes the tokens expired for longer than {@code identity.token-purge.retention}. */
    @Transactional
    public PurgedTokens purge() {
        Instant cutoff = clock.instant().minus(properties.retention());

        return new PurgedTokens(
                queries.deleteRefreshFamiliesExpiredBefore(cutoff),
                queries.deleteVerificationTokensExpiredBefore(cutoff),
                queries.deleteResetTokensExpiredBefore(cutoff)
        );
    }
}
