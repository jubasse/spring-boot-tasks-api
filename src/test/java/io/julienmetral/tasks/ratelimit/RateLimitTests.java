package io.julienmetral.tasks.ratelimit;

import io.julienmetral.tasks.ratelimit.services.RateLimitKeys;
import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.ratelimit.repositories.RateLimitQueries;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limits are small, so that a few requests reach them, and the clock is pinned, so that every request of a test
 * falls in the same window and {@code Retry-After} is exact. Counters are shared by every test of this context: each
 * test uses its own client addresses and emails.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class, RateLimitTests.PinnedClockConfiguration.class})
@SpringBootTest(properties = {
        "rate-limit.enabled=true",
        // Never fires while the tests run, so no window disappears under an assertion
        "rate-limit.purge-cron=0 0 0 1 1 *",
        "rate-limit.login-per-ip.requests=" + RateLimitTests.LOGIN_PER_IP,
        "rate-limit.login-per-ip.window=PT1M",
        "rate-limit.login-per-email.requests=" + RateLimitTests.LOGIN_PER_EMAIL,
        "rate-limit.login-per-email.window=PT15M",
        "rate-limit.sign-up-per-ip.requests=" + RateLimitTests.SIGN_UP_PER_IP,
        "rate-limit.sign-up-per-ip.window=PT1H",
        "rate-limit.password-reset-per-ip.requests=" + RateLimitTests.PASSWORD_RESET_PER_IP,
        "rate-limit.password-reset-per-ip.window=PT1H",
        "rate-limit.password-reset-per-email.requests=" + RateLimitTests.PASSWORD_RESET_PER_EMAIL,
        "rate-limit.password-reset-per-email.window=PT1H",
        "rate-limit.verification-resend-per-user.requests=" + RateLimitTests.VERIFICATION_RESEND_PER_USER,
        "rate-limit.verification-resend-per-user.window=PT1H"
})
@AutoConfigureMockMvc
class RateLimitTests {

    static final int LOGIN_PER_IP = 5;
    static final int LOGIN_PER_EMAIL = 2;
    static final int SIGN_UP_PER_IP = 2;
    static final int PASSWORD_RESET_PER_IP = 3;
    static final int PASSWORD_RESET_PER_EMAIL = 2;
    static final int VERIFICATION_RESEND_PER_USER = 2;

    // 40 s before the next minute, 4 min 40 s before the next quarter hour and 49 min 40 s before the next hour
    static final Instant NOW = Instant.parse("2100-01-01T00:10:20Z");

    static final String UNTIL_NEXT_MINUTE = "40";
    static final String UNTIL_NEXT_QUARTER = "280";
    static final String UNTIL_NEXT_HOUR = "2980";

    private static final String PASSWORD = "password123";

    // Delivery is asynchronous: "no email sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(500);

    private static final int PARALLEL_REQUESTS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class PinnedClockConfiguration {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock(NOW);
        }
    }

    static final class SettableClock extends Clock {

        private volatile Instant instant;

        SettableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RateLimitQueries queries;

    @Autowired
    private SettableClock clock;

    @AfterEach
    void pinTheClockAgain() {
        clock.set(NOW);
    }

    @Nested
    class Login {

        @Test
        void loginIsRefusedPastTheLimitPerAddressAcrossDifferentEmails() throws Exception {
            String address = uniqueAddress();

            for (int attempt = 0; attempt < LOGIN_PER_IP; attempt++) {
                login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            }

            expectTooManyRequests(login(address, uniqueEmail(), PASSWORD), UNTIL_NEXT_MINUTE);
        }

        @Test
        void loginIsRefusedPastTheLimitPerEmailAcrossDifferentAddresses() throws Exception {
            String email = uniqueEmail();

            for (int attempt = 0; attempt < LOGIN_PER_EMAIL; attempt++) {
                login(uniqueAddress(), email, PASSWORD).andExpect(status().isUnauthorized());
            }

            expectTooManyRequests(login(uniqueAddress(), email, PASSWORD), UNTIL_NEXT_QUARTER);
        }

        @Test
        void loginEmailLimitMatchesTheEmailIgnoringCase() throws Exception {
            String email = uniqueEmail();

            login(uniqueAddress(), email, PASSWORD).andExpect(status().isUnauthorized());
            login(uniqueAddress(), email.toUpperCase(), PASSWORD).andExpect(status().isUnauthorized());

            expectTooManyRequests(
                    login(uniqueAddress(), email.replace("example.com", "Example.COM"), PASSWORD), UNTIL_NEXT_QUARTER);
        }

        @Test
        void failedLoginsCountSoTheRightPasswordIsRefusedPastTheLimit() throws Exception {
            String email = uniqueEmail();
            UUID userId = signUpUser(email);

            for (int attempt = 0; attempt < LOGIN_PER_EMAIL; attempt++) {
                login(uniqueAddress(), email, "wrong-password").andExpect(status().isUnauthorized());
            }

            expectTooManyRequests(login(uniqueAddress(), email, PASSWORD), UNTIL_NEXT_QUARTER);
            assertThat(refreshTokenCount(userId)).isZero();
        }

        @Test
        void loginThatReachesTheLimitStillSucceeds() throws Exception {
            String email = uniqueEmail();
            signUpUser(email);

            login(uniqueAddress(), email, "wrong-password").andExpect(status().isUnauthorized());
            login(uniqueAddress(), email, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());

            expectTooManyRequests(login(uniqueAddress(), email, PASSWORD), UNTIL_NEXT_QUARTER);
        }

        @Test
        void refusedLoginAnswersAProblemWithRetryAfter() throws Exception {
            String address = uniqueAddress();
            for (int attempt = 0; attempt < LOGIN_PER_IP; attempt++) {
                login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            }

            login(address, uniqueEmail(), PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, UNTIL_NEXT_MINUTE))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.title").value("Too many requests"))
                    .andExpect(jsonPath("$.detail").value(
                            "Too many requests, try again in " + UNTIL_NEXT_MINUTE + " seconds"));
        }

        @Test
        void loginRefusedByTheEmailLimitDoesNotUseTheAddressAllowance() throws Exception {
            String email = uniqueEmail();
            for (int attempt = 0; attempt < LOGIN_PER_EMAIL; attempt++) {
                login(uniqueAddress(), email, PASSWORD).andExpect(status().isUnauthorized());
            }
            String address = uniqueAddress();

            expectTooManyRequests(login(address, email, PASSWORD), UNTIL_NEXT_QUARTER);

            assertThat(counter(RateLimitKeys.address("login", address))).isZero();
            for (int attempt = 0; attempt < LOGIN_PER_IP; attempt++) {
                login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            }
        }
        @Test
        void loginWithAnEmailLongerThanTheCounterKeyColumnIsAnsweredAsAnyUnknownEmail() throws Exception {
            // Nameprep drops soft hyphens (JSON escapes here), so @Email accepts this 500-character domain
            String email = "a@b" + "\\u00AD".repeat(500) + ".com";

            MockHttpServletResponse response = login(uniqueAddress(), email, PASSWORD).andReturn().getResponse();

            assertThat(response.getStatus()).isIn(400, 401);
        }

        @Test
        void loginIsAcceptedAgainInTheNextWindow() throws Exception {
            String address = uniqueAddress();
            for (int attempt = 0; attempt < LOGIN_PER_IP; attempt++) {
                login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            }
            expectTooManyRequests(login(address, uniqueEmail(), PASSWORD), UNTIL_NEXT_MINUTE);

            clock.set(Instant.parse("2100-01-01T00:11:00Z"));

            login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class SignUp {

        @Test
        void signUpIsRefusedPastTheLimitPerAddress() throws Exception {
            String address = uniqueAddress();

            for (int attempt = 0; attempt < SIGN_UP_PER_IP; attempt++) {
                signUp(address, uniqueEmail()).andExpect(status().isCreated());
            }

            expectTooManyRequests(signUp(address, uniqueEmail()), UNTIL_NEXT_HOUR);
        }

        @Test
        void refusedSignUpCreatesNoUserAndSendsNoEmail() throws Exception {
            String address = uniqueAddress();
            for (int attempt = 0; attempt < SIGN_UP_PER_IP; attempt++) {
                signUp(address, uniqueEmail()).andExpect(status().isCreated());
            }
            String email = uniqueEmail();

            expectTooManyRequests(signUp(address, email), UNTIL_NEXT_HOUR);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
            Thread.sleep(NO_MAIL_GRACE_PERIOD);
            assertThat(mailpit.countTo(email)).isZero();
        }

        @Test
        void refusedSignUpsAreNotCounted() throws Exception {
            String address = uniqueAddress();
            for (int attempt = 0; attempt < SIGN_UP_PER_IP; attempt++) {
                signUp(address, uniqueEmail()).andExpect(status().isCreated());
            }

            expectTooManyRequests(signUp(address, uniqueEmail()), UNTIL_NEXT_HOUR);
            expectTooManyRequests(signUp(address, uniqueEmail()), UNTIL_NEXT_HOUR);

            assertThat(counter(RateLimitKeys.address("sign-up", address))).isEqualTo(SIGN_UP_PER_IP);
        }

        @Test
        void invalidSignUpIsNotCounted() throws Exception {
            String address = uniqueAddress();

            mockMvc.perform(post("/api/v1/users")
                            .with(from(address))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "not-an-email", "password": "%s", "displayName": "Rate limit"}
                                    """.formatted(PASSWORD)))
                    .andExpect(status().isBadRequest());

            assertThat(counter(RateLimitKeys.address("sign-up", address))).isZero();
        }
    }

    @Nested
    class PasswordReset {

        @Test
        void passwordResetRequestIsRefusedPastTheLimitPerAddress() throws Exception {
            String address = uniqueAddress();

            for (int attempt = 0; attempt < PASSWORD_RESET_PER_IP; attempt++) {
                requestReset(address, uniqueEmail()).andExpect(status().isAccepted());
            }

            expectTooManyRequests(requestReset(address, uniqueEmail()), UNTIL_NEXT_HOUR);
        }

        @Test
        void passwordResetRequestIsRefusedPastTheLimitPerEmailAndSendsNoMoreEmails() throws Exception {
            String email = uniqueEmail();
            signUpUser(email);

            for (int attempt = 0; attempt < PASSWORD_RESET_PER_EMAIL; attempt++) {
                requestReset(uniqueAddress(), email).andExpect(status().isAccepted());
            }
            // The verification email of the sign-up, then one reset email per accepted request
            awaitEmailCount(email, 1 + PASSWORD_RESET_PER_EMAIL);

            expectTooManyRequests(requestReset(uniqueAddress(), email.toUpperCase()), UNTIL_NEXT_HOUR);

            Thread.sleep(NO_MAIL_GRACE_PERIOD);
            assertThat(mailpit.countTo(email)).isEqualTo(1 + PASSWORD_RESET_PER_EMAIL);
        }

        @Test
        void emailNoAccountUsesIsRefusedExactlyLikeTheEmailOfAnAccount() throws Exception {
            String accountEmail = uniqueEmail();
            signUpUser(accountEmail);
            String unusedEmail = uniqueEmail();

            for (int attempt = 0; attempt < PASSWORD_RESET_PER_EMAIL; attempt++) {
                requestReset(uniqueAddress(), accountEmail).andExpect(status().isAccepted());
                requestReset(uniqueAddress(), unusedEmail).andExpect(status().isAccepted());
            }

            MockHttpServletResponse forAccount = expectTooManyRequests(
                    requestReset(uniqueAddress(), accountEmail), UNTIL_NEXT_HOUR);
            MockHttpServletResponse forUnusedEmail = expectTooManyRequests(
                    requestReset(uniqueAddress(), unusedEmail), UNTIL_NEXT_HOUR);

            assertThat(forUnusedEmail.getContentAsString()).isEqualTo(forAccount.getContentAsString());
            assertThat(forUnusedEmail.getHeader(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(forAccount.getHeader(HttpHeaders.RETRY_AFTER));
            assertThat(forUnusedEmail.getContentType()).isEqualTo(forAccount.getContentType());
            Thread.sleep(NO_MAIL_GRACE_PERIOD);
            assertThat(mailpit.countTo(unusedEmail)).isZero();
        }

        @Test
        void passwordResetConfirmIsRefusedPastTheLimitPerAddressEvenWithAValidToken() throws Exception {
            String email = uniqueEmail();
            UUID userId = signUpUser(email);
            requestReset(uniqueAddress(), email).andExpect(status().isAccepted());
            awaitEmailCount(email, 2);
            String token = Mailpit.extractToken(mailpit.latestTextTo(email));
            String address = uniqueAddress();

            for (int attempt = 0; attempt < PASSWORD_RESET_PER_IP; attempt++) {
                confirmReset(address, "unknown-" + UUID.randomUUID()).andExpect(status().isBadRequest());
            }

            expectTooManyRequests(confirmReset(address, token), UNTIL_NEXT_HOUR);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT used_at FROM password_reset_tokens WHERE user_id = ?", Timestamp.class, userId)).isNull();
            login(uniqueAddress(), email, PASSWORD).andExpect(status().isOk());
        }
        @Test
        void passwordResetRequestForAnEmailThatOutgrowsTheCounterKeyColumnWhenLowerCasedIsAccepted()
                throws Exception {
            // 320 characters, the @Size limit; lower-casing turns each capital dotted I into two characters, and
            // nameprep drops the soft hyphens, so @Email accepts the domain (JSON escapes here)
            String email = "\\u0130".repeat(64) + "@b" + "\\u00AD".repeat(250) + ".com";

            requestReset(uniqueAddress(), email).andExpect(status().isAccepted());
        }

        @Test
        void passwordResetRequestsAndConfirmationsShareTheAddressLimit() throws Exception {
            String address = uniqueAddress();

            requestReset(address, uniqueEmail()).andExpect(status().isAccepted());
            confirmReset(address, "unknown-" + UUID.randomUUID()).andExpect(status().isBadRequest());
            requestReset(address, uniqueEmail()).andExpect(status().isAccepted());

            expectTooManyRequests(confirmReset(address, "unknown-" + UUID.randomUUID()), UNTIL_NEXT_HOUR);
            expectTooManyRequests(requestReset(address, uniqueEmail()), UNTIL_NEXT_HOUR);
        }
    }

    @Nested
    class VerificationResend {

        @Test
        void verificationResendIsRefusedPastTheLimitPerUserAndSendsNoMoreEmails() throws Exception {
            String email = uniqueEmail();
            UUID userId = signUpUser(email);

            for (int attempt = 0; attempt < VERIFICATION_RESEND_PER_USER; attempt++) {
                resend(uniqueAddress(), userId).andExpect(status().isNoContent());
            }
            // The verification email of the sign-up, then one per accepted resend
            awaitEmailCount(email, 1 + VERIFICATION_RESEND_PER_USER);

            expectTooManyRequests(resend(uniqueAddress(), userId), UNTIL_NEXT_HOUR);

            Thread.sleep(NO_MAIL_GRACE_PERIOD);
            assertThat(mailpit.countTo(email)).isEqualTo(1 + VERIFICATION_RESEND_PER_USER);
        }

        @Test
        void verificationResendLimitOfOneUserLeavesOtherUsersOfTheSameAddressAlone() throws Exception {
            String address = uniqueAddress();
            UUID limitedUser = signUpUser(uniqueEmail());
            UUID otherUser = signUpUser(uniqueEmail());
            for (int attempt = 0; attempt < VERIFICATION_RESEND_PER_USER; attempt++) {
                resend(address, limitedUser).andExpect(status().isNoContent());
            }

            expectTooManyRequests(resend(address, limitedUser), UNTIL_NEXT_HOUR);

            resend(address, otherUser).andExpect(status().isNoContent());
        }
    }

    @Nested
    class Independence {

        @Test
        void countersOfDifferentAddressesAreIndependent() throws Exception {
            String limitedAddress = "198.51.100.10";
            String otherAddress = "198.51.100.11";
            for (int attempt = 0; attempt < LOGIN_PER_IP; attempt++) {
                login(limitedAddress, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            }

            expectTooManyRequests(login(limitedAddress, uniqueEmail(), PASSWORD), UNTIL_NEXT_MINUTE);

            login(otherAddress, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        void countersOfDifferentEmailsAreIndependent() throws Exception {
            String limitedEmail = uniqueEmail();
            for (int attempt = 0; attempt < LOGIN_PER_EMAIL; attempt++) {
                login(uniqueAddress(), limitedEmail, PASSWORD).andExpect(status().isUnauthorized());
            }

            expectTooManyRequests(login(uniqueAddress(), limitedEmail, PASSWORD), UNTIL_NEXT_QUARTER);

            login(uniqueAddress(), uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        void limitOfOneEndpointLeavesTheOtherEndpointsOfTheAddressAlone() throws Exception {
            String address = uniqueAddress();
            for (int attempt = 0; attempt < SIGN_UP_PER_IP; attempt++) {
                signUp(address, uniqueEmail()).andExpect(status().isCreated());
            }

            expectTooManyRequests(signUp(address, uniqueEmail()), UNTIL_NEXT_HOUR);

            login(address, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
            requestReset(address, uniqueEmail()).andExpect(status().isAccepted());
        }
    }

    @Nested
    class Concurrency {

        @Test
        void parallelChecksOfOneAddressAcceptExactlyTheLimit() throws Exception {
            String address = uniqueAddress();

            List<Boolean> accepted = inParallel(() -> accepts(() -> rateLimiter.login(address, uniqueEmail())));

            assertThat(accepted).filteredOn(Boolean::booleanValue).hasSize(LOGIN_PER_IP);
            assertThat(counter(RateLimitKeys.address("login", address))).isEqualTo(LOGIN_PER_IP);
        }

        @Test
        void parallelChecksOfOneEmailAcceptExactlyTheLimit() throws Exception {
            String email = uniqueEmail();

            List<Boolean> accepted = inParallel(
                    () -> accepts(() -> rateLimiter.passwordResetRequest(uniqueAddress(), email)));

            assertThat(accepted).filteredOn(Boolean::booleanValue).hasSize(PASSWORD_RESET_PER_EMAIL);
            assertThat(counter(RateLimitKeys.email("password-reset", email))).isEqualTo(PASSWORD_RESET_PER_EMAIL);
        }

        @Test
        void parallelLoginsFromOneAddressAreAnsweredUntilTheLimitThenRefused() throws Exception {
            String address = uniqueAddress();

            List<Integer> statuses = inParallel(() -> login(address, uniqueEmail(), PASSWORD)
                    .andReturn()
                    .getResponse()
                    .getStatus());

            assertThat(statuses).filteredOn(status -> status == 401).hasSize(LOGIN_PER_IP);
            assertThat(statuses).filteredOn(status -> status == 429).hasSize(PARALLEL_REQUESTS - LOGIN_PER_IP);
        }

        @Test
        void parallelIncrementsOfOneKeyEachReturnADifferentCount() throws Exception {
            String key = "test:" + UUID.randomUUID();
            Instant windowStart = Instant.parse("2000-01-01T00:00:00Z");

            List<Integer> counts = inParallel(() -> queries.increment(key, windowStart));

            assertThat(counts).containsExactlyInAnyOrderElementsOf(
                    IntStream.rangeClosed(1, PARALLEL_REQUESTS).boxed().toList());
            assertThat(counter(key)).isEqualTo(PARALLEL_REQUESTS);
        }

        private boolean accepts(Runnable check) {
            try {
                check.run();
                return true;
            } catch (RateLimitExceededException refused) {
                return false;
            }
        }

        private <T> List<T> inParallel(Callable<T> request) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();

            try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
                for (int thread = 0; thread < PARALLEL_REQUESTS; thread++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return request.call();
                    }));
                }
                start.countDown();

                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(30, TimeUnit.SECONDS));
                }
                return results;
            }
        }
    }

    @Nested
    class Queries {

        private final Instant windowStart = Instant.parse("2000-01-01T00:00:00Z");

        @Test
        void incrementStartsAtOneAndCountsEveryRequestOfTheWindow() {
            String key = "test:" + UUID.randomUUID();

            assertThat(queries.increment(key, windowStart)).isEqualTo(1);
            assertThat(queries.increment(key, windowStart)).isEqualTo(2);
            assertThat(queries.increment(key, windowStart)).isEqualTo(3);
        }

        @Test
        void incrementCountsEachKeyAndEachWindowSeparately() {
            String key = "test:" + UUID.randomUUID();
            String otherKey = "test:" + UUID.randomUUID();
            queries.increment(key, windowStart);
            queries.increment(key, windowStart);

            assertThat(queries.increment(key, windowStart.plus(Duration.ofMinutes(1)))).isEqualTo(1);
            assertThat(queries.increment(otherKey, windowStart)).isEqualTo(1);
            assertThat(queries.increment(key, windowStart)).isEqualTo(3);
        }

        @Test
        void incrementKeepsTheMillisecondsOfTheWindowStart() {
            String key = "test:" + UUID.randomUUID();
            Instant start = Instant.parse("2000-01-01T00:00:00.123Z");

            queries.increment(key, start);

            assertThat(windowStartsOf(key)).containsExactly(start);
        }

        @Test
        void deleteWindowsStartedBeforeRemovesOnlyTheWindowsStartedBeforeTheCutoff() {
            String key = "test:" + UUID.randomUUID();
            Instant cutoff = Instant.parse("1999-06-01T00:00:00Z");
            queries.increment(key, cutoff.minus(Duration.ofDays(1)));
            queries.increment(key, cutoff.minusMillis(1));
            queries.increment(key, cutoff);
            queries.increment(key, cutoff.plus(Duration.ofHours(1)));

            int deleted = queries.deleteWindowsStartedBefore(cutoff);

            assertThat(deleted).isGreaterThanOrEqualTo(2);
            assertThat(windowStartsOf(key)).containsExactly(cutoff, cutoff.plus(Duration.ofHours(1)));
        }

        private List<Instant> windowStartsOf(String key) {
            return jdbcTemplate.queryForList(
                            "SELECT window_start FROM rate_limit_counters WHERE bucket_key = ? ORDER BY window_start",
                            Timestamp.class,
                            key)
                    .stream()
                    .map(Timestamp::toInstant)
                    .toList();
        }
    }

    private ResultActions login(String address, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private ResultActions signUp(String address, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                .with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "displayName": "Rate limit test"}
                        """.formatted(email, PASSWORD)));
    }

    /** Signs up from an address of its own, so the sign-up limit of the address under test is untouched. */
    private UUID signUpUser(String email) throws Exception {
        String location = signUp(uniqueAddress(), email)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions requestReset(String address, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/request")
                .with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private ResultActions confirmReset(String address, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "newPassword": "brand-new-password"}
                        """.formatted(token)));
    }

    private ResultActions resend(String address, UUID userId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                .with(from(address))
                .with(jwt()
                        .jwt(jwt -> jwt.claim("uid", userId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static MockHttpServletResponse expectTooManyRequests(ResultActions result, String retryAfter)
            throws Exception {
        return result
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, retryAfter))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andReturn()
                .getResponse();
    }

    private int counter(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(count), 0) FROM rate_limit_counters WHERE bucket_key = ?",
                Integer.class,
                key);

        return count == null ? 0 : count;
    }

    private int refreshTokenCount(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);

        return count == null ? 0 : count;
    }

    private void awaitEmailCount(String email, int expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> mailpit.countTo(email) >= expected);

        assertThat(mailpit.countTo(email)).isEqualTo(expected);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String uniqueAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Varies the /64 prefix: addresses of one /64 share a counter
        return "2001:db8:%x:%x::1".formatted(random.nextInt(0x10000), random.nextInt(0x10000));
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
