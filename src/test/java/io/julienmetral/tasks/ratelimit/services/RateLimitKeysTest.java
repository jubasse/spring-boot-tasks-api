package io.julienmetral.tasks.ratelimit.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeysTest {

    @Test
    void addressKeyHidesTheAddressBehindAFixedLengthHash() {
        String key = RateLimitKeys.address("login", "198.51.100.7");

        assertThat(key).startsWith("login:ip:").doesNotContain("198.51.100.7").hasSize("login:ip:".length() + 64);
    }

    @Test
    void differentIpv4AddressesHaveDifferentKeys() {
        assertThat(RateLimitKeys.address("login", "198.51.100.7"))
                .isNotEqualTo(RateLimitKeys.address("login", "198.51.100.8"));
    }

    @Test
    void ipv6AddressesOfTheSameSlash64ShareOneKey() {
        assertThat(RateLimitKeys.address("login", "2001:db8:1:2::1"))
                .isEqualTo(RateLimitKeys.address("login", "2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isEqualTo(RateLimitKeys.address("login", "2001:0db8:0001:0002:0000:0000:0000:0042"));
    }

    @Test
    void ipv6AddressesOfDifferentSlash64HaveDifferentKeys() {
        assertThat(RateLimitKeys.address("login", "2001:db8:1:2::1"))
                .isNotEqualTo(RateLimitKeys.address("login", "2001:db8:1:3::1"));
    }

    @Test
    void anAddressThatIsNotALiteralIsStillCounted() {
        assertThat(RateLimitKeys.address("login", "unknown"))
                .isEqualTo(RateLimitKeys.address("login", "unknown"))
                .isNotEqualTo(RateLimitKeys.address("login", "other"));
    }

    @Test
    void emailKeyIgnoresCaseAndSurroundingSpaces() {
        assertThat(RateLimitKeys.email("login", "  Jane.Doe@Example.COM "))
                .isEqualTo(RateLimitKeys.email("login", "jane.doe@example.com"));
    }

    @Test
    void emailKeyHasAFixedLengthWhateverTheEmail() {
        String longEmail = "İ".repeat(300) + "@example.com";

        assertThat(RateLimitKeys.email("password-reset", longEmail))
                .hasSize("password-reset:email:".length() + 64)
                .doesNotContain("example.com");
    }

    @Test
    void keysOfDifferentEndpointsNeverCollide() {
        assertThat(RateLimitKeys.address("login", "198.51.100.7"))
                .isNotEqualTo(RateLimitKeys.address("sign-up", "198.51.100.7"));
        assertThat(RateLimitKeys.email("login", "jane@example.com"))
                .isNotEqualTo(RateLimitKeys.email("password-reset", "jane@example.com"));
    }

    @Test
    void userKeyKeepsTheUserId() {
        UUID userId = UUID.fromString("01a0dd4e-d6a0-7f44-97c4-dd07e904e7f4");

        assertThat(RateLimitKeys.user("verification-resend", userId))
                .isEqualTo("verification-resend:user:01a0dd4e-d6a0-7f44-97c4-dd07e904e7f4");
    }
}
