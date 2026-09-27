package io.julienmetral.tasks.identity.entities;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");

    @Nested
    class MarkActive {

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
            assertThat(user.getProfile().getAnonymizedAt()).isNull();
        }
    }

    @Nested
    class Profile {

        @Test
        void newUserHasItsOwnProfile() {
            assertThat(new User().getProfile()).isNotNull();
            assertThat(new User().getProfile()).isNotSameAs(new User().getProfile());
        }

        @Test
        void displayNameIsStoredOnTheProfile() {
            User user = new User();

            user.setDisplayName("Alice");

            assertThat(user.getProfile().getDisplayName()).isEqualTo("Alice");
            assertThat(user.getDisplayName()).isEqualTo("Alice");
        }
    }

    @Nested
    class ProfileStatusSync {

        private User activeUser() {
            User user = new User();
            user.setEmailVerifiedAt(NOW);
            return user;
        }

        @Test
        void newUserIsUnverified() {
            assertThat(new User().getProfile().getStatus()).isEqualTo(UserStatus.UNVERIFIED);
        }

        @Test
        void verifyingTheEmailMakesTheProfileActive() {
            User user = new User();

            user.setEmailVerifiedAt(NOW);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        void clearingTheVerificationMakesTheProfileUnverifiedAgain() {
            User user = activeUser();

            user.setEmailVerifiedAt(null);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.UNVERIFIED);
        }

        @Test
        void disablingMakesTheProfileDisabled() {
            User user = activeUser();

            user.setEnabled(false);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void disablingAnUnverifiedUserMakesTheProfileDisabled() {
            User user = new User();

            user.setEnabled(false);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void reEnablingAVerifiedUserMakesTheProfileActive() {
            User user = activeUser();
            user.setEnabled(false);

            user.setEnabled(true);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        void reEnablingAnUnverifiedUserMakesTheProfileUnverified() {
            User user = new User();
            user.setEnabled(false);

            user.setEnabled(true);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.UNVERIFIED);
        }

        @Test
        void verifyingADisabledUserKeepsTheProfileDisabled() {
            User user = new User();
            user.setEnabled(false);

            user.setEmailVerifiedAt(NOW);

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        void markDeletedMakesTheProfileDeleted() {
            User user = activeUser();

            user.markDeleted();

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.DELETED);
        }

        @Test
        void markDeletedOnADisabledUserMakesTheProfileDeleted() {
            User user = activeUser();
            user.setEnabled(false);

            user.markDeleted();

            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.DELETED);
        }

        @Test
        void profileStatusAlwaysMatchesTheAccountStatusBeforeDeletion() {
            User user = new User();
            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.of(user));

            user.setEmailVerifiedAt(NOW);
            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.of(user));

            user.setEnabled(false);
            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.of(user));

            user.setEmailVerifiedAt(null);
            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.of(user));

            user.setEnabled(true);
            assertThat(user.getProfile().getStatus()).isEqualTo(UserStatus.of(user));
        }
    }
}
