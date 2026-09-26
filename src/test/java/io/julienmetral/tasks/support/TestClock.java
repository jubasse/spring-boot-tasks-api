package io.julienmetral.tasks.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application's {@link Clock} in integration tests. It follows the system time until a test pins it
 * ({@link #set}) or moves it ({@link #advance}), and {@link ResetAfterEach} puts it back after every test, so one
 * context serves both the tests that need a fixed time and those that do not.
 */
@TestComponent
@Primary
public class TestClock extends Clock {

    private volatile Instant pinned;

    private volatile Duration offset = Duration.ZERO;

    public void set(Instant instant) {
        pinned = instant;
        offset = Duration.ZERO;
    }

    public void advance(Duration duration) {
        Instant current = pinned;

        if (current != null) {
            pinned = current.plus(duration);
        } else {
            offset = offset.plus(duration);
        }
    }

    public void reset() {
        pinned = null;
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        Instant current = pinned;

        return current != null ? current : Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    public static final class ResetAfterEach implements AfterEachCallback {

        @Override
        public void afterEach(ExtensionContext context) {
            SpringExtension.getApplicationContext(context).getBeanProvider(TestClock.class).ifAvailable(TestClock::reset);
        }
    }
}
