package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountStateTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "true, true, ACTIVE",
            "true, false, UNVERIFIED",
            "false, true, DISABLED",
            "false, false, DISABLED"
    })
    void statusIsTheOneTheLoadedAccountWouldHave(boolean enabled, boolean verified, UserStatus expected) {
        Instant emailVerifiedAt = verified ? VERIFIED_AT : null;
        User account = new User();
        account.setEnabled(enabled);
        account.setEmailVerifiedAt(emailVerifiedAt);

        assertThat(new AccountState(enabled, emailVerifiedAt).status())
                .isEqualTo(expected)
                .isEqualTo(UserStatus.of(account));
    }
}
