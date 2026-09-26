package io.julienmetral.tasks.identity.entities;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");

    @Test
    void markActiveRecordsTheActivityAndClearsTheInactivityWarning() {
        User user = new User();
        user.setLastActiveAt(Instant.parse("2027-01-01T00:00:00Z"));
        user.setInactivityWarnedAt(Instant.parse("2030-05-01T00:00:00Z"));

        user.markActive(NOW);

        assertThat(user.getLastActiveAt()).isEqualTo(NOW);
        assertThat(user.getInactivityWarnedAt()).isNull();
    }

    @Test
    void markActiveOnANeverWarnedUserOnlyRecordsTheActivity() {
        User user = new User();

        user.markActive(NOW);

        assertThat(user.getLastActiveAt()).isEqualTo(NOW);
        assertThat(user.getInactivityWarnedAt()).isNull();
    }

    @Test
    void markActiveLeavesTheLastLoginAndTheAnonymizationUntouched() {
        Instant lastLogin = Instant.parse("2029-01-01T00:00:00Z");
        User user = new User();
        user.setLastLoginAt(lastLogin);

        user.markActive(NOW);

        assertThat(user.getLastLoginAt()).isEqualTo(lastLogin);
        assertThat(user.getAnonymizedAt()).isNull();
    }
}
