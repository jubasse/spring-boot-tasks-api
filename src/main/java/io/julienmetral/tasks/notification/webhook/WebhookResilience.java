package io.julienmetral.tasks.notification.webhook;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A Resilience4j circuit breaker and bulkhead per receiving host, used from code because annotations cannot key them
 * by host. The breaker stops calling a host that keeps failing, for everybody's deliveries to it, until one test call
 * succeeds; the bulkhead caps the calls in flight to one host.
 * <p>
 * Their state lives in each instance's memory: several instances each learn that a host is down. The durable rules
 * (retries, disabling an endpoint) live in the database.
 */
class WebhookResilience {

    /** A call that may go ahead; hand it back to {@link #release} once the call ended. */
    record Permit(CircuitBreaker breaker, Bulkhead bulkhead, long startNanos) {
    }

    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;

    WebhookResilience(WebhookProperties properties, Clock clock, MeterRegistry meterRegistry) {
        WebhookProperties.HostCircuitBreaker settings = properties.circuitBreaker();

        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindow((int) settings.window().toSeconds(), settings.minimumCalls(),
                        SlidingWindowType.TIME_BASED)
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .clock(clock)
                .build());
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.concurrentCallsPerHost())
                .maxWaitDuration(Duration.ZERO)
                .build());

        // One aggregate series: a series per host would grow with the number of subscribers
        Gauge.builder("webhook.circuit.breakers.open", breakers, registry -> registry.getAllCircuitBreakers().stream()
                        .filter(breaker -> breaker.getState() == CircuitBreaker.State.OPEN)
                        .count())
                .description("Receiving hosts whose circuit breaker refuses calls")
                .register(meterRegistry);
    }

    /** Empty when the host's breaker is open or as many calls as allowed are already in flight to it. */
    Optional<Permit> tryAcquire(String host) {
        CircuitBreaker breaker = breakers.circuitBreaker(host);
        Bulkhead bulkhead = bulkheads.bulkhead(host);

        if (!breaker.tryAcquirePermission()) {
            return Optional.empty();
        }

        if (!bulkhead.tryAcquirePermission()) {
            breaker.releasePermission();
            return Optional.empty();
        }

        return Optional.of(new Permit(breaker, bulkhead, System.nanoTime()));
    }

    /**
     * Records how the call went: a 5xx, a 429 or no answer counts against the host; another status shows the host
     * answers. A call the address filter refused never left, so it counts neither way.
     */
    void release(Permit permit, Integer statusCode, boolean refusedByFilter) {
        long elapsed = System.nanoTime() - permit.startNanos();

        try {
            if (refusedByFilter) {
                permit.breaker().releasePermission();
            } else if (statusCode == null || statusCode >= 500 || statusCode == 429) {
                permit.breaker().onError(elapsed, TimeUnit.NANOSECONDS, new HostFailure(statusCode));
            } else {
                permit.breaker().onSuccess(elapsed, TimeUnit.NANOSECONDS);
            }
        } finally {
            permit.bulkhead().onComplete();
        }
    }

    // What the breaker records as the error of a failed call; never thrown
    private static final class HostFailure extends RuntimeException {

        HostFailure(Integer statusCode) {
            super(statusCode == null ? "No answer" : "HTTP " + statusCode, null, false, false);
        }
    }
}
