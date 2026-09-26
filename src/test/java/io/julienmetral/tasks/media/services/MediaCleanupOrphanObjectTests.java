package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaCleanupReport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RustFS cannot backdate an object, so this class moves the clock forward instead: every object and media row
 * created here is already past the orphan grace period. It runs in its own context, with its own containers, so the
 * other test classes keep a real clock.
 */
@Import({TestcontainersConfiguration.class, MediaCleanupOrphanObjectTests.FutureClock.class})
@SpringBootTest
class MediaCleanupOrphanObjectTests extends AbstractMediaCleanupTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class FutureClock {

        @Bean
        @Primary
        Clock futureClock() {
            return Clock.offset(Clock.systemUTC(), Duration.ofDays(3));
        }
    }

    @Test
    void oldObjectWithoutMediaRowIsDeleted() {
        String key = putObjectWithoutMediaRow();

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.deletedOrphanObjects()).isGreaterThanOrEqualTo(1);
        assertThat(objectExists(key)).isFalse();
    }

    @Test
    void oldObjectsOfReferencedMediaAreKept() {
        User user = createUser();
        Media attachment = attachedToTask(createTask(user), user);
        UUID recentlyDeletedTaskId = createTask(user);
        Media attachmentOfRecentlyDeletedTask = attachedToTask(recentlyDeletedTaskId, user);
        softDeleteTask(recentlyDeletedTaskId, WITHIN_RETENTION);
        Media avatar = avatarOf(user);

        cleanupService.cleanUp();

        for (Media media : new Media[]{attachment, attachmentOfRecentlyDeletedTask, avatar}) {
            assertThat(mediaExists(media)).isTrue();
            assertThat(objectExists(media.getStorageKey())).isTrue();
        }
        assertThat(avatarMediaIdOf(user)).isEqualTo(avatar.getId());
    }

    @Test
    void unreferencedMediaIsDeletedWithItsObjectWithoutCountingItAsOrphanObject() {
        User user = createUser();
        Media media = storeAttachment(user);

        MediaCleanupReport report = cleanupService.cleanUp();

        assertThat(report.deletedMedia()).isGreaterThanOrEqualTo(1);
        assertThat(mediaExists(media)).isFalse();
        assertThat(objectExists(media.getStorageKey())).isFalse();

        MediaCleanupReport second = cleanupService.cleanUp();

        assertThat(second.deletedOrphanObjects()).isZero();
    }

    @Test
    void secondRunRightAfterDeletesNothingMore() {
        User user = createUser();
        String orphanKey = putObjectWithoutMediaRow();
        Media unreferenced = storeAttachment(user);
        Media attachment = attachedToTask(createTask(user), user);

        cleanupService.cleanUp();
        MediaCleanupReport second = cleanupService.cleanUp();

        assertThat(second).isEqualTo(new MediaCleanupReport(false, 0, 0, 0, 0));
        assertThat(objectExists(orphanKey)).isFalse();
        assertThat(mediaExists(unreferenced)).isFalse();
        assertThat(mediaExists(attachment)).isTrue();
        assertThat(objectExists(attachment.getStorageKey())).isTrue();
    }

    @Test
    void orphanObjectDeletionWaitsForTheCommit() {
        String key = putObjectWithoutMediaRow();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(cleanupService.cleanUp().deletedOrphanObjects()).isGreaterThanOrEqualTo(1);
            assertThat(objectExists(key)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(objectExists(key)).isTrue();

        cleanupService.cleanUp();

        assertThat(objectExists(key)).isFalse();
    }
}
