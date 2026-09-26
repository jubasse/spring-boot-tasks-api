package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.services.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retries and dead-lettering of the profile photo queue, with reads from storage made to fail through a spy. The
 * retry backoff is shortened so that a message reaches the dead-letter queue in well under a second. The same spy
 * also lets a test act while the worker's transaction is open.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=" + AvatarQueueDeadLetterTests.MAX_RETRIES,
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=100ms"
})
class AvatarQueueDeadLetterTests extends AbstractAvatarApiTests {

    static final int MAX_RETRIES = 1;

    private static final long DEAD_LETTER_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();

    @MockitoSpyBean
    private ObjectStorage objectStorage;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @AfterEach
    void purgeDeadLetterQueue() {
        amqpAdmin.purgeQueue(AvatarQueues.DEAD_LETTER, false);
    }

    @Test
    void unreadableUploadIsRetriedThenDeadLetteredAndStaysPending() throws Exception {
        User user = createUser(UserRole.USER);
        doThrow(new StorageUnavailableException(new RuntimeException("storage down")))
                .when(objectStorage).open(startsWith("avatar-upload/"));

        uploadAvatar(user, asUser(user), "me.png", opaquePng())
                .andExpect(status().isAccepted());
        String uploadKey = pendingAvatarStorageKey(user);

        Message deadLetter = rabbitTemplate.receive(AvatarQueues.DEAD_LETTER, DEAD_LETTER_TIMEOUT_MILLIS);

        assertThat(deadLetter).isNotNull();
        assertThat(jsonMapper.readValue(deadLetter.getBody(), AvatarUploaded.class))
                .isEqualTo(new AvatarUploaded(user.getId(), pendingAvatarMediaId(user)));
        assertRejectedFromProcessQueue(deadLetter);
        verify(objectStorage, times(1 + MAX_RETRIES)).open(uploadKey);

        assertThat(pendingAvatarStorageKey(user)).isEqualTo(uploadKey);
        assertThat(headObject(uploadKey).contentLength()).isPositive();
        assertThat(avatarStorageKey(user)).isNull();
        assertThat(userJson(user).get("avatarPending").asBoolean()).isTrue();
    }

    @Test
    void failedProcessingKeepsTheCurrentPhoto() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);
        doThrow(new StorageUnavailableException(new RuntimeException("storage down")))
                .when(objectStorage).open(startsWith("avatar-upload/"));

        uploadAvatar(user, asUser(user), "me.png", opaquePng())
                .andExpect(status().isAccepted());

        assertThat(rabbitTemplate.receive(AvatarQueues.DEAD_LETTER, DEAD_LETTER_TIMEOUT_MILLIS)).isNotNull();
        assertThat(avatarStorageKey(user)).isEqualTo(key);
        assertThat(headObject(key).contentLength()).isPositive();
        assertThat(userJson(user).get("avatarUrl").asString()).contains(key);
    }

    @Test
    void transientStorageFailureIsRetriedAndNotDeadLettered() throws Exception {
        User user = createUser(UserRole.USER);
        doThrow(new StorageUnavailableException(new RuntimeException("storage down")))
                .doCallRealMethod()
                .when(objectStorage).open(startsWith("avatar-upload/"));

        uploadAndAwaitAvatar(user, asUser(user), "me.png", opaquePng());

        assertThat(avatarStorageKey(user)).isNotNull();
        assertThat(avatarUploadCountUploadedBy(user)).isZero();
        assertThat(rabbitTemplate.receive(AvatarQueues.DEAD_LETTER, 500)).isNull();
    }

    @Test
    void accountDisabledWhileItsPhotoIsProcessedIsNotReenabled() throws Exception {
        User user = createUser(UserRole.USER);
        AtomicReference<CompletableFuture<Void>> disable = new AtomicReference<>();
        doAnswer(invocation -> {
            // Stands for a concurrent POST /users/{id}/disable: it waits for the worker's row lock, then commits
            disable.set(CompletableFuture.runAsync(() -> jdbcTemplate.update(
                    "update users set enabled = false where id = ?",
                    user.getId()
            )));

            return invocation.callRealMethod();
        }).when(objectStorage).open(startsWith("avatar-upload/"));

        uploadAvatar(user, asUser(user), "me.png", opaquePng())
                .andExpect(status().isAccepted());
        awaitProcessed(user);
        disable.get().get(10, TimeUnit.SECONDS);

        assertThat(avatarStorageKey(user)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from users where id = ?",
                Boolean.class,
                user.getId()
        )).isFalse();
    }

    private UUID pendingAvatarMediaId(User user) {
        return jdbcTemplate.queryForObject(
                "select pending_avatar_media_id from users where id = ?",
                UUID.class,
                user.getId()
        );
    }

    private static void assertRejectedFromProcessQueue(Message deadLetter) {
        List<Map<String, ?>> deaths = deadLetter.getMessageProperties().getXDeathHeader();

        assertThat(deaths).singleElement().satisfies(death -> {
            assertThat(death.get("queue")).isEqualTo(AvatarQueues.PROCESS);
            assertThat(death.get("reason")).isEqualTo("rejected");
            assertThat(death.get("count")).isEqualTo(1L);
        });
    }
}
