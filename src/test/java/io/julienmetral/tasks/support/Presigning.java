package io.julienmetral.tasks.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

public final class Presigning {

    private Presigning() {}

    /**
     * Signing is deterministic within a second, the resolution of {@code X-Amz-Date}: two URLs of
     * the same object signed in the same second match even when the second one was signed anew.
     * Waiting for the next second lets a test tell a reused URL from a new one.
     */
    public static void waitForTheNextSecond() {
        long second = Instant.now().getEpochSecond();

        await().pollInterval(Duration.ofMillis(20))
                .until(() -> Instant.now().getEpochSecond() > second);
    }
}
