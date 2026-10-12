package io.julienmetral.tasks.support;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Awaitility's first check comes 100 ms after the call and then every 100 ms, so each of the
 * suite's await calls paid a fixed tenth of a second even when the condition was already true.
 * Awaitility 4.3.0 reads no system property, hence this listener, registered through {@code
 * META-INF/services}.
 */
public class AwaitilityDefaults implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Awaitility.setDefaultPollDelay(Duration.ZERO);
        Awaitility.setDefaultPollInterval(Duration.ofMillis(20));
    }
}
