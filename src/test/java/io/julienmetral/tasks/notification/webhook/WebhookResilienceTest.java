package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.support.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookResilienceTest {

    private static final String HOST = "hooks.example.com";

    private static final String OTHER_HOST = "other.example.com";

    private static final Instant START = Instant.parse("2026-09-28T10:00:00Z");

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private static final Duration OPEN_DURATION = Duration.ofMinutes(1);

    private static final int MINIMUM_CALLS = 5;

    private final TestClock clock = new TestClock();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final WebhookResilience resilience;

    WebhookResilienceTest() {
        clock.set(START);
        // Bound from nothing, the properties take their defaults, which application.yaml repeats
        resilience = new WebhookResilience(
                new Binder(new MapConfigurationPropertySource()).bindOrCreate("webhooks", WebhookProperties.class),
                clock,
                meterRegistry
        );
    }

    // Circuit breaker: when it opens

    @Test
    void breakerStaysClosedUnderFiveCallsEvenWhenAllOfThemFail() {
        failures(HOST, MINIMUM_CALLS - 1);

        assertThat(letsACallThrough(HOST)).isTrue();
        assertThat(openBreakers()).isZero();
    }

    @Test
    void breakerOpensOnceHalfOfTheCallsInTheWindowFailed() {
        calls(HOST, 204, 3);
        failures(HOST, 2);
        assertThat(letsACallThrough(HOST)).as("2 failures in 5 calls").isTrue();

        failures(HOST, 1);

        assertThat(letsACallThrough(HOST)).as("3 failures in 6 calls").isFalse();
        assertThat(openBreakers()).isOne();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {500, 502, 503, 504, 522})
    void serverErrorsAndCallsWithoutAnAnswerAreFailures(Integer statusCode) {
        calls(HOST, statusCode, MINIMUM_CALLS);

        assertThat(letsACallThrough(HOST)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204, 307, 400, 401, 403, 404, 410, 422, 429})
    void otherStatusesAreSuccessesThatWeighAgainstTheFailures(int statusCode) {
        calls(HOST, statusCode, 3);
        failures(HOST, 2);
        assertThat(letsACallThrough(HOST)).as("2 failures after 3 calls answered " + statusCode).isTrue();

        failures(HOST, 1);

        assertThat(letsACallThrough(HOST)).as("3 failures after 3 calls answered " + statusCode).isFalse();
    }

    @Test
    void callRefusedByTheAddressFilterCountsNeitherAsAFailureNorAsASuccess() {
        for (int i = 0; i < 6; i++) {
            resilience.release(acquire(HOST), null, true);
        }
        assertThat(letsACallThrough(HOST)).as("after 6 refused calls").isTrue();

        failures(HOST, MINIMUM_CALLS);

        assertThat(letsACallThrough(HOST)).as("after 6 refused calls and 5 failures").isFalse();
    }

    @Test
    void callsOlderThanTheWindowStopCounting() {
        failures(HOST, MINIMUM_CALLS - 1);
        clock.advance(WINDOW);

        failures(HOST, 1);

        assertThat(letsACallThrough(HOST)).isTrue();
    }

    @Test
    void callsWithinTheWindowAddUp() {
        failures(HOST, MINIMUM_CALLS - 1);
        clock.advance(WINDOW.minusSeconds(1));

        failures(HOST, 1);

        assertThat(letsACallThrough(HOST)).isFalse();
    }

    // Circuit breaker: open, then testing the host again

    @Test
    void openBreakerRefusesEveryCallToItsHostOnly() {
        open(HOST);

        for (int i = 0; i < 10; i++) {
            assertThat(resilience.tryAcquire(HOST)).isEmpty();
        }
        assertThat(letsACallThrough(OTHER_HOST)).isTrue();
    }

    @Test
    void breakersOfTwoHostsCountTheirOwnCallsOnly() {
        failures(HOST, MINIMUM_CALLS - 1);
        failures(OTHER_HOST, MINIMUM_CALLS - 1);

        assertThat(letsACallThrough(HOST)).isTrue();
        assertThat(letsACallThrough(OTHER_HOST)).isTrue();
    }

    @Test
    void hostNamesDifferingOnlyInLetterCaseShareOneBreaker() {
        open(HOST);

        assertThat(resilience.tryAcquire(HOST.toUpperCase(Locale.ROOT))).isEmpty();
    }

    @Test
    void openBreakerRefusesCallsForTheWholeOpenDuration() {
        open(HOST);

        clock.advance(OPEN_DURATION);

        assertThat(resilience.tryAcquire(HOST)).isEmpty();
    }

    @Test
    void afterTheOpenDurationOnlyOneCallTestsTheHost() {
        open(HOST);
        clock.advance(OPEN_DURATION.plusMillis(1));

        Optional<WebhookResilience.Permit> test = resilience.tryAcquire(HOST);

        assertThat(test).isPresent();
        assertThat(resilience.tryAcquire(HOST)).as("second call while the test call runs").isEmpty();
        assertThat(openBreakers()).as("a breaker testing its host").isZero();
    }

    @Test
    void successfulTestCallClosesTheBreaker() {
        open(HOST);
        clock.advance(OPEN_DURATION.plusMillis(1));

        resilience.release(acquire(HOST), 204, false);

        assertThat(letsACallThrough(HOST)).isTrue();
        assertThat(letsACallThrough(HOST)).isTrue();
        assertThat(openBreakers()).isZero();
    }

    @Test
    void closedAgainBreakerStartsCountingFromNothing() {
        open(HOST);
        clock.advance(OPEN_DURATION.plusMillis(1));
        resilience.release(acquire(HOST), 204, false);

        failures(HOST, MINIMUM_CALLS - 1);

        assertThat(letsACallThrough(HOST)).isTrue();
    }

    @Test
    void failedTestCallOpensTheBreakerForAnotherOpenDuration() {
        open(HOST);
        clock.advance(OPEN_DURATION.plusMillis(1));

        resilience.release(acquire(HOST), 503, false);

        assertThat(resilience.tryAcquire(HOST)).isEmpty();
        clock.advance(OPEN_DURATION);
        assertThat(resilience.tryAcquire(HOST)).isEmpty();
        clock.advance(Duration.ofMillis(1));
        assertThat(resilience.tryAcquire(HOST)).isPresent();
    }

    @Test
    void testCallRefusedByTheAddressFilterLeavesTheTestToTheNextCall() {
        open(HOST);
        clock.advance(OPEN_DURATION.plusMillis(1));

        resilience.release(acquire(HOST), null, true);

        assertThat(resilience.tryAcquire(HOST)).isPresent();
    }

    // Bulkhead

    @Test
    void bulkheadRefusesAThirdCallInFlightToOneHost() {
        acquire(HOST);
        acquire(HOST);

        assertThat(resilience.tryAcquire(HOST)).isEmpty();
        assertThat(letsACallThrough(OTHER_HOST)).isTrue();
    }

    @Test
    void hostNamesDifferingOnlyInLetterCaseShareOneBulkhead() {
        acquire(HOST);
        acquire(HOST);

        assertThat(resilience.tryAcquire(HOST.toUpperCase(Locale.ROOT))).isEmpty();
    }

    @Test
    void releasedPermitFreesItsSlot() {
        WebhookResilience.Permit first = acquire(HOST);
        acquire(HOST);

        resilience.release(first, 204, false);

        assertThat(resilience.tryAcquire(HOST)).isPresent();
    }

    @Test
    void failedCallAlsoFreesItsSlot() {
        WebhookResilience.Permit first = acquire(HOST);
        acquire(HOST);

        resilience.release(first, null, false);

        assertThat(resilience.tryAcquire(HOST)).isPresent();
    }

    @Test
    void callRefusedByTheBreakerTakesNoBulkheadSlot() {
        open(HOST);
        for (int i = 0; i < 5; i++) {
            assertThat(resilience.tryAcquire(HOST)).isEmpty();
        }
        clock.advance(OPEN_DURATION.plusMillis(1));

        resilience.release(acquire(HOST), 204, false);

        acquire(HOST);
        assertThat(resilience.tryAcquire(HOST)).as("second call in flight").isPresent();
    }

    @Test
    void callRefusedByTheBulkheadCountsNeitherAsAFailureNorAsASuccess() {
        WebhookResilience.Permit first = acquire(HOST);
        WebhookResilience.Permit second = acquire(HOST);
        for (int i = 0; i < 10; i++) {
            assertThat(resilience.tryAcquire(HOST)).isEmpty();
        }
        resilience.release(first, 204, false);
        resilience.release(second, 204, false);

        failures(HOST, 2);
        assertThat(letsACallThrough(HOST)).as("2 failures in 4 calls").isTrue();
        failures(HOST, 1);

        assertThat(letsACallThrough(HOST)).as("3 failures in 5 calls").isFalse();
    }

    // Gauge

    @Test
    void gaugeCountsTheHostsWhoseBreakerRefusesCalls() {
        assertThat(openBreakers()).isZero();
        open(HOST);
        clock.advance(Duration.ofSeconds(30));
        open(OTHER_HOST);
        assertThat(openBreakers()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(31));

        resilience.release(acquire(HOST), 204, false);

        assertThat(openBreakers()).isOne();
    }

    private WebhookResilience.Permit acquire(String host) {
        return resilience.tryAcquire(host).orElseThrow(() -> new AssertionError("no permit for " + host));
    }

    // Hands the permit back as a call the address filter refused, which the breaker does not record
    private boolean letsACallThrough(String host) {
        Optional<WebhookResilience.Permit> permit = resilience.tryAcquire(host);
        permit.ifPresent(granted -> resilience.release(granted, null, true));
        return permit.isPresent();
    }

    private void calls(String host, Integer statusCode, int count) {
        for (int i = 0; i < count; i++) {
            resilience.release(acquire(host), statusCode, false);
        }
    }

    private void failures(String host, int count) {
        calls(host, 500, count);
    }

    private void open(String host) {
        failures(host, MINIMUM_CALLS);
        assertThat(resilience.tryAcquire(host)).as("breaker of " + host + " open").isEmpty();
    }

    private double openBreakers() {
        return meterRegistry.get("webhook.circuit.breakers.open").gauge().value();
    }
}
