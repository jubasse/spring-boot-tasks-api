package io.julienmetral.tasks.notification.webhook;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Removes the circuit breaker and the bulkhead of every receiving host before each test. Every receiver of the
 * delivery tests is 127.0.0.1, whose breaker lives as long as the shared context: the failures of earlier tests, of
 * the same class or another, opened it and the later tests sent nothing (30 of the 86 tests of
 * {@link WebhookDeliveryTests} once failed that way).
 * <p>
 * Removed rather than reset, a breaker comes back on the clock of the test that next uses it: once the clock goes
 * back, as the {@code TestClock} does between tests, its time window drops old calls one second per call instead of
 * by time. {@link WebhookResilience} offers no other way in than its fields.
 */
final class ForgetWebhookHostsBeforeEach implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        WebhookResilience resilience = SpringExtension.getApplicationContext(context).getBean(WebhookResilience.class);
        CircuitBreakerRegistry breakers = (CircuitBreakerRegistry) ReflectionTestUtils.getField(resilience, "breakers");
        BulkheadRegistry bulkheads = (BulkheadRegistry) ReflectionTestUtils.getField(resilience, "bulkheads");

        breakers.getAllCircuitBreakers().stream().map(CircuitBreaker::getName).toList().forEach(breakers::remove);
        bulkheads.getAllBulkheads().stream().map(Bulkhead::getName).toList().forEach(bulkheads::remove);
    }
}
