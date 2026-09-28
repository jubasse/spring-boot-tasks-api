package io.julienmetral.tasks.notification.webhook;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Forgets the circuit breaker and the bulkhead of every receiving host before each test. Every receiver of the
 * delivery tests is 127.0.0.1, whose breaker lives as long as the shared context: the failures of earlier tests, of
 * the same class or another, opened it and the later tests sent nothing (30 of the 86 tests of
 * {@link WebhookDeliveryTests} once failed that way).
 * <p>
 * Removed rather than reset, a breaker comes back on the clock of the test that next uses it: once the clock goes
 * back, as the {@code TestClock} does between tests, its time window drops old calls one second per call instead of
 * by time.
 */
final class ForgetWebhookHostsBeforeEach implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(WebhookResilience.class).forgetAllHosts();
    }
}
