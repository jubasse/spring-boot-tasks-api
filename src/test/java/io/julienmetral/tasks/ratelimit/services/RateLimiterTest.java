package io.julienmetral.tasks.ratelimit.services;

import io.julienmetral.tasks.config.RateLimitProperties;
import io.julienmetral.tasks.config.RateLimitProperties.Limit;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    // 42.5 s into its minute, 2 min 42.5 s into its quarter hour and 17 min 42.5 s into its hour
    private static final Instant NOW = Instant.parse("2026-09-26T10:17:42.500Z");

    private static final Instant MINUTE_START = Instant.parse("2026-09-26T10:17:00Z");

    private static final Instant QUARTER_START = Instant.parse("2026-09-26T10:15:00Z");

    private static final Instant HOUR_START = Instant.parse("2026-09-26T10:00:00Z");

    private static final Duration UNTIL_NEXT_MINUTE = Duration.parse("PT17.5S");

    private static final Duration UNTIL_NEXT_QUARTER = Duration.parse("PT12M17.5S");

    private static final Duration UNTIL_NEXT_HOUR = Duration.parse("PT42M17.5S");

    private static final String ADDRESS = "203.0.113.7";

    private static final String EMAIL = "alice@example.com";

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private static final String LOGIN_IP_KEY = "login:ip:" + ADDRESS;

    private static final String LOGIN_EMAIL_KEY = "login:email:" + EMAIL;

    private static final String SIGN_UP_IP_KEY = "sign-up:ip:" + ADDRESS;

    private static final String PASSWORD_RESET_IP_KEY = "password-reset:ip:" + ADDRESS;

    private static final String PASSWORD_RESET_EMAIL_KEY = "password-reset:email:" + EMAIL;

    private static final String VERIFICATION_RESEND_KEY = "verification-resend:user:" + USER_ID;

    // Each limit differs from the others, so a key checked against another key's limit fails a test
    private static final int LOGIN_PER_IP = 30;
    private static final int LOGIN_PER_EMAIL = 10;
    private static final int SIGN_UP_PER_IP = 20;
    private static final int PASSWORD_RESET_PER_IP = 25;
    private static final int PASSWORD_RESET_PER_EMAIL = 3;
    private static final int VERIFICATION_RESEND_PER_USER = 4;

    @Mock
    private RateLimitQueries queries;

    @Nested
    class Keys {

        @Test
        void loginCountsTheAddressPerMinuteAndTheEmailPerQuarterHour() {
            limiter().login(ADDRESS, EMAIL);

            verify(queries).increment(LOGIN_IP_KEY, MINUTE_START);
            verify(queries).increment(LOGIN_EMAIL_KEY, QUARTER_START);
            verifyNoMoreInteractions(queries);
        }

        @Test
        void signUpCountsTheAddressPerHour() {
            limiter().signUp(ADDRESS);

            verify(queries).increment(SIGN_UP_IP_KEY, HOUR_START);
            verifyNoMoreInteractions(queries);
        }

        @Test
        void passwordResetRequestCountsTheAddressAndTheEmailPerHour() {
            limiter().passwordResetRequest(ADDRESS, EMAIL);

            verify(queries).increment(PASSWORD_RESET_IP_KEY, HOUR_START);
            verify(queries).increment(PASSWORD_RESET_EMAIL_KEY, HOUR_START);
            verifyNoMoreInteractions(queries);
        }

        @Test
        void passwordResetConfirmCountsTheSameAddressKeyAsTheRequest() {
            limiter().passwordResetConfirm(ADDRESS);

            verify(queries).increment(PASSWORD_RESET_IP_KEY, HOUR_START);
            verifyNoMoreInteractions(queries);
        }

        @Test
        void verificationResendCountsTheUserPerHour() {
            limiter().verificationResend(USER_ID);

            verify(queries).increment(VERIFICATION_RESEND_KEY, HOUR_START);
            verifyNoMoreInteractions(queries);
        }

        @Test
        void ipv6AddressIsUsedAsGiven() {
            limiter().signUp("2001:db8::1");

            verify(queries).increment("sign-up:ip:2001:db8::1", HOUR_START);
        }
    }

    @Nested
    class EmailNormalization {

        @Test
        void loginEmailIsTrimmedAndLowerCased() {
            limiter().login(ADDRESS, "  Alice@Example.COM \t");

            verify(queries).increment(LOGIN_EMAIL_KEY, QUARTER_START);
        }

        @Test
        void passwordResetEmailIsTrimmedAndLowerCased() {
            limiter().passwordResetRequest(ADDRESS, " ALICE@example.com ");

            verify(queries).increment(PASSWORD_RESET_EMAIL_KEY, HOUR_START);
        }

        @Test
        void lowerCasingDoesNotDependOnTheDefaultLocale() {
            Locale defaultLocale = Locale.getDefault();
            // In Turkish, "I".toLowerCase() is a dotless i, which would give the same email a second key
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            try {
                limiter().login(ADDRESS, "ALICE@EXAMPLE.COM");
            } finally {
                Locale.setDefault(defaultLocale);
            }

            verify(queries).increment(LOGIN_EMAIL_KEY, QUARTER_START);
        }
    }

    @Nested
    class Windows {

        @Test
        void oneMinuteWindowEndsAtTheNextMinute() {
            when(queries.increment(LOGIN_IP_KEY, MINUTE_START)).thenReturn(LOGIN_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().login(ADDRESS, EMAIL))).isEqualTo(UNTIL_NEXT_MINUTE);
        }

        @Test
        void quarterHourWindowEndsAtTheNextQuarterHour() {
            when(queries.increment(LOGIN_IP_KEY, MINUTE_START)).thenReturn(1);
            when(queries.increment(LOGIN_EMAIL_KEY, QUARTER_START)).thenReturn(LOGIN_PER_EMAIL + 1);

            assertThat(retryAfterOf(() -> limiter().login(ADDRESS, EMAIL))).isEqualTo(UNTIL_NEXT_QUARTER);
        }

        @Test
        void oneHourWindowEndsAtTheNextHour() {
            when(queries.increment(SIGN_UP_IP_KEY, HOUR_START)).thenReturn(SIGN_UP_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().signUp(ADDRESS))).isEqualTo(UNTIL_NEXT_HOUR);
        }

        @Test
        void requestAtTheFirstInstantOfAWindowWaitsTheWholeWindow() {
            Instant quarterStart = Instant.parse("2026-09-26T10:30:00Z");
            when(queries.increment(LOGIN_IP_KEY, quarterStart)).thenReturn(1);
            when(queries.increment(LOGIN_EMAIL_KEY, quarterStart)).thenReturn(LOGIN_PER_EMAIL + 1);

            assertThat(retryAfterOf(() -> limiter(quarterStart).login(ADDRESS, EMAIL)))
                    .isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        void requestAtTheLastMillisecondOfAWindowWaitsOneMillisecond() {
            Instant lastMillisecond = Instant.parse("2026-09-26T10:59:59.999Z");
            when(queries.increment(SIGN_UP_IP_KEY, HOUR_START)).thenReturn(SIGN_UP_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter(lastMillisecond).signUp(ADDRESS)))
                    .isEqualTo(Duration.ofMillis(1));
        }

        @Test
        void nextWindowIsCountedUnderItsOwnStart() {
            limiter(Instant.parse("2026-09-26T11:00:00Z")).signUp(ADDRESS);

            verify(queries).increment(SIGN_UP_IP_KEY, Instant.parse("2026-09-26T11:00:00Z"));
        }

        @Test
        void windowsAreAlignedOnTheEpochRatherThanOnTheHour() {
            RateLimitProperties sevenMinutes = properties(true, new Limit(1, Duration.ofMinutes(7)));
            // 10:13 is a multiple of 7 minutes since 1970-01-01T00:00Z, although 13 is not a multiple of 7
            Instant windowStart = Instant.parse("2026-09-26T10:13:00Z");
            when(queries.increment(SIGN_UP_IP_KEY, windowStart)).thenReturn(2);

            assertThat(retryAfterOf(() -> new RateLimiter(queries, sevenMinutes, clockAt(NOW)).signUp(ADDRESS)))
                    .isEqualTo(Duration.parse("PT2M17.5S"));
        }
    }

    @Nested
    class Limits {

        @Test
        void requestThatReachesTheLimitIsAccepted() {
            when(queries.increment(SIGN_UP_IP_KEY, HOUR_START)).thenReturn(SIGN_UP_PER_IP);

            assertThatNoException().isThrownBy(() -> limiter().signUp(ADDRESS));
        }

        @Test
        void requestAfterTheLimitIsRefusedWithTheTimeLeftInTheWindow() {
            when(queries.increment(SIGN_UP_IP_KEY, HOUR_START)).thenReturn(SIGN_UP_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().signUp(ADDRESS))).isEqualTo(UNTIL_NEXT_HOUR);
        }

        @Test
        void loginAtBothLimitsIsAccepted() {
            when(queries.increment(LOGIN_IP_KEY, MINUTE_START)).thenReturn(LOGIN_PER_IP);
            when(queries.increment(LOGIN_EMAIL_KEY, QUARTER_START)).thenReturn(LOGIN_PER_EMAIL);

            assertThatNoException().isThrownBy(() -> limiter().login(ADDRESS, EMAIL));
        }

        @Test
        void loginOverTheAddressLimitIsRefusedWithoutCountingTheEmail() {
            when(queries.increment(LOGIN_IP_KEY, MINUTE_START)).thenReturn(LOGIN_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().login(ADDRESS, EMAIL))).isEqualTo(UNTIL_NEXT_MINUTE);

            verify(queries, never()).increment(startsWith("login:email:"), any());
        }

        @Test
        void loginOverTheEmailLimitIsRefusedAfterCountingTheAddress() {
            when(queries.increment(LOGIN_IP_KEY, MINUTE_START)).thenReturn(1);
            when(queries.increment(LOGIN_EMAIL_KEY, QUARTER_START)).thenReturn(LOGIN_PER_EMAIL + 1);

            assertThat(retryAfterOf(() -> limiter().login(ADDRESS, EMAIL))).isEqualTo(UNTIL_NEXT_QUARTER);

            verify(queries).increment(LOGIN_IP_KEY, MINUTE_START);
        }

        @Test
        void passwordResetRequestAtBothLimitsIsAccepted() {
            when(queries.increment(PASSWORD_RESET_IP_KEY, HOUR_START)).thenReturn(PASSWORD_RESET_PER_IP);
            when(queries.increment(PASSWORD_RESET_EMAIL_KEY, HOUR_START)).thenReturn(PASSWORD_RESET_PER_EMAIL);

            assertThatNoException().isThrownBy(() -> limiter().passwordResetRequest(ADDRESS, EMAIL));
        }

        @Test
        void passwordResetRequestOverTheAddressLimitIsRefusedWithoutCountingTheEmail() {
            when(queries.increment(PASSWORD_RESET_IP_KEY, HOUR_START)).thenReturn(PASSWORD_RESET_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().passwordResetRequest(ADDRESS, EMAIL)))
                    .isEqualTo(UNTIL_NEXT_HOUR);

            verify(queries, never()).increment(startsWith("password-reset:email:"), any());
        }

        @Test
        void passwordResetRequestOverTheEmailLimitIsRefusedAfterCountingTheAddress() {
            when(queries.increment(PASSWORD_RESET_IP_KEY, HOUR_START)).thenReturn(1);
            when(queries.increment(PASSWORD_RESET_EMAIL_KEY, HOUR_START)).thenReturn(PASSWORD_RESET_PER_EMAIL + 1);

            assertThat(retryAfterOf(() -> limiter().passwordResetRequest(ADDRESS, EMAIL)))
                    .isEqualTo(UNTIL_NEXT_HOUR);

            verify(queries).increment(PASSWORD_RESET_IP_KEY, HOUR_START);
        }

        @Test
        void passwordResetConfirmOverTheAddressLimitIsRefused() {
            when(queries.increment(PASSWORD_RESET_IP_KEY, HOUR_START)).thenReturn(PASSWORD_RESET_PER_IP + 1);

            assertThat(retryAfterOf(() -> limiter().passwordResetConfirm(ADDRESS))).isEqualTo(UNTIL_NEXT_HOUR);
        }

        @Test
        void verificationResendAtTheLimitIsAcceptedAndTheNextIsRefused() {
            when(queries.increment(VERIFICATION_RESEND_KEY, HOUR_START))
                    .thenReturn(VERIFICATION_RESEND_PER_USER, VERIFICATION_RESEND_PER_USER + 1);

            assertThatNoException().isThrownBy(() -> limiter().verificationResend(USER_ID));
            assertThat(retryAfterOf(() -> limiter().verificationResend(USER_ID))).isEqualTo(UNTIL_NEXT_HOUR);
        }
    }

    @Nested
    class WhenDisabled {

        @Test
        void disabledLimiterCountsNothingAndRefusesNothing() {
            RateLimiter disabled = new RateLimiter(queries, properties(false), clockAt(NOW));

            disabled.login(ADDRESS, EMAIL);
            disabled.signUp(ADDRESS);
            disabled.passwordResetRequest(ADDRESS, EMAIL);
            disabled.passwordResetConfirm(ADDRESS);
            disabled.verificationResend(USER_ID);

            verifyNoInteractions(queries);
        }
    }

    private RateLimiter limiter() {
        return limiter(NOW);
    }

    private RateLimiter limiter(Instant now) {
        return new RateLimiter(queries, properties(true), clockAt(now));
    }

    private static Duration retryAfterOf(ThrowingCallable request) {
        Throwable thrown = catchThrowable(request);

        assertThat(thrown).isInstanceOf(RateLimitExceededException.class);

        return ((RateLimitExceededException) thrown).getRetryAfter();
    }

    private static Clock clockAt(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static RateLimitProperties properties(boolean enabled) {
        return properties(enabled, new Limit(SIGN_UP_PER_IP, Duration.ofHours(1)));
    }

    private static RateLimitProperties properties(boolean enabled, Limit signUpPerIp) {
        return new RateLimitProperties(
                enabled,
                "0 20 * * * *",
                new Limit(LOGIN_PER_IP, Duration.ofMinutes(1)),
                new Limit(LOGIN_PER_EMAIL, Duration.ofMinutes(15)),
                signUpPerIp,
                new Limit(PASSWORD_RESET_PER_IP, Duration.ofHours(1)),
                new Limit(PASSWORD_RESET_PER_EMAIL, Duration.ofHours(1)),
                new Limit(VERIFICATION_RESEND_PER_USER, Duration.ofHours(1))
        );
    }
}
