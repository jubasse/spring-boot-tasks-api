package io.julienmetral.tasks.identity.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.julienmetral.tasks.config.ExpiredTokenPurgeProperties;
import io.julienmetral.tasks.identity.repositories.ExpiredTokenQueries;
import io.julienmetral.tasks.identity.services.ExpiredTokenPurgeService.PurgedTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpiredTokenPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-31T04:15:00Z");

    private static final Duration RETENTION = Duration.ofDays(7);

    private static final Instant CUTOFF = NOW.minus(RETENTION);

    @Mock private ExpiredTokenQueries queries;

    private ExpiredTokenPurgeService service;

    @BeforeEach
    void setUp() {
        service =
                new ExpiredTokenPurgeService(
                        queries,
                        new ExpiredTokenPurgeProperties(true, "0 15 4 * * *", RETENTION),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgesEveryTokenTypeExpiredBeforeNowMinusTheRetention() {
        when(queries.deleteRefreshFamiliesExpiredBefore(CUTOFF)).thenReturn(3);
        when(queries.deleteVerificationTokensExpiredBefore(CUTOFF)).thenReturn(2);
        when(queries.deleteResetTokensExpiredBefore(CUTOFF)).thenReturn(1);

        assertThat(service.purge()).isEqualTo(new PurgedTokens(3, 2, 1));
    }

    @Test
    void purgeThatDeletedNothingIsEmpty() {
        assertThat(service.purge().isEmpty()).isTrue();
        assertThat(new PurgedTokens(0, 0, 1).isEmpty()).isFalse();
    }
}
