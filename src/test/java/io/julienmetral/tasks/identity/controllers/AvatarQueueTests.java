package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile photo worker end to end: the upload answers 202, {@code AvatarProcessingListener} consumes
 * {@code avatar.process} from the RabbitMQ container and replaces the photo in RustFS and Postgres.
 */
class AvatarQueueTests extends AbstractAvatarApiTests {

    // A stale message changes nothing: that can only be checked once the queue is empty and a grace period is over
    private static final Duration STALE_MESSAGE_GRACE_PERIOD = Duration.ofMillis(800);

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Test
    void uploadIsAcceptedAsPendingThenTheWorkerReplacesThePhoto() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, png(filled(64, 64, Color.RED)));
        String previousKey = avatarStorageKey(user);
        String uploadKey;

        MessageListenerContainer worker = avatarWorker();
        worker.stop();
        try {
            uploadAvatar(user, asUser(user), "me.png", png(filled(640, 480, Color.BLUE)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.avatarPending").value(true))
                    .andExpect(jsonPath("$.avatarUrl", containsString(previousKey)));

            uploadKey = pendingAvatarStorageKey(user);

            assertThat(uploadKey).matches("avatar-upload/[0-9a-f-]{36}");
            assertThat(headObject(uploadKey).contentType()).isEqualTo("image/png");
            assertThat(avatarStorageKey(user)).isEqualTo(previousKey);
            assertThat(userJson(user).get("avatarPending").asBoolean()).isTrue();
        } finally {
            worker.start();
        }

        awaitProcessed(user);

        String newKey = avatarStorageKey(user);
        JsonNode body = userJson(user);

        assertThat(body.get("avatarPending").asBoolean()).isFalse();
        assertThat(newKey).matches("avatar/[0-9a-f-]{36}").isNotEqualTo(previousKey);
        assertThat(body.get("avatarUrl").asString()).contains(newKey);

        BufferedImage avatar = decode(objectBytes(newKey));

        assertThat(avatar.getWidth()).isEqualTo(256);
        assertThat(avatar.getHeight()).isEqualTo(256);
        assertThat(isBlue(avatar.getRGB(128, 128))).isTrue();

        assertThat(mediaRowCount(uploadKey)).isZero();
        awaitObjectMissing(uploadKey);
        assertThat(mediaRowCount(previousKey)).isZero();
        awaitObjectMissing(previousKey);
        assertThat(mediaCountUploadedBy(user)).isOne();
    }

    @Test
    void onlyTheLastOfTwoQuickUploadsBecomesThePhoto() throws Exception {
        User user = createUser(UserRole.USER);
        String firstKey;
        String secondKey;

        MessageListenerContainer worker = avatarWorker();
        worker.stop();
        try {
            uploadAvatar(user, asUser(user), "first.png", png(filled(64, 64, Color.RED)))
                    .andExpect(status().isAccepted());
            firstKey = pendingAvatarStorageKey(user);

            uploadAvatar(user, asUser(user), "second.png", png(filled(64, 64, Color.BLUE)))
                    .andExpect(status().isAccepted());
            secondKey = pendingAvatarStorageKey(user);

            assertThat(secondKey).isNotEqualTo(firstKey);
            assertThat(mediaRowCount(firstKey)).isZero();
            assertObjectMissing(firstKey);
        } finally {
            worker.start();
        }

        awaitProcessed(user);
        awaitQueueDrained();

        assertThat(isBlue(decode(objectBytes(avatarStorageKey(user))).getRGB(128, 128))).isTrue();
        assertThat(mediaRowCount(secondKey)).isZero();
        awaitObjectMissing(secondKey);
        assertThat(avatarUploadCountUploadedBy(user)).isZero();
        assertThat(mediaCountUploadedBy(user)).isOne();
    }

    @Test
    void removingWhileAPhotoIsPendingClearsBothAndTheWorkerThenDoesNothing() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, opaquePng());
        String previousKey = avatarStorageKey(user);
        String uploadKey;

        MessageListenerContainer worker = avatarWorker();
        worker.stop();
        try {
            uploadAvatar(user, asUser(user), "me.png", png(filled(64, 64, Color.BLUE)))
                    .andExpect(status().isAccepted());
            uploadKey = pendingAvatarStorageKey(user);

            mockMvc.perform(delete(AVATAR, user.getId()).with(asUser(user)))
                    .andExpect(status().isNoContent());

            assertThat(avatarStorageKey(user)).isNull();
            assertThat(pendingAvatarStorageKey(user)).isNull();
            assertThat(mediaRowCount(previousKey)).isZero();
            assertThat(mediaRowCount(uploadKey)).isZero();
            assertObjectMissing(previousKey);
            assertObjectMissing(uploadKey);
        } finally {
            worker.start();
        }

        awaitQueueDrained();
        Thread.sleep(STALE_MESSAGE_GRACE_PERIOD);

        assertThat(avatarStorageKey(user)).isNull();
        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(mediaCountUploadedBy(user)).isZero();

        JsonNode body = userJson(user);

        assertThat(body.get("avatarPending").asBoolean()).isFalse();
        assertThat(body.get("avatarUrl").isNull()).isTrue();
    }

    @Test
    void messageForAnUploadThatIsNotPendingChangesNothing() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);

        rabbitTemplate.convertAndSend(AvatarQueues.PROCESS, new AvatarUploaded(user.getId(), UUID.randomUUID()));
        rabbitTemplate.convertAndSend(AvatarQueues.PROCESS, new AvatarUploaded(UUID.randomUUID(), UUID.randomUUID()));

        awaitQueueDrained();
        Thread.sleep(STALE_MESSAGE_GRACE_PERIOD);

        assertThat(avatarStorageKey(user)).isEqualTo(key);
        assertThat(headObject(key).contentLength()).isPositive();
        assertThat(mediaCountUploadedBy(user)).isOne();
    }

    @Test
    void pdfIsRefusedSynchronouslyAndNothingIsStored() throws Exception {
        User user = createUser(UserRole.USER);
        byte[] pdf = """
                %%PDF-1.4
                %% %s
                1 0 obj << /Type /Catalog >> endobj
                trailer << /Root 1 0 R >>
                %%%%EOF
                """.formatted(UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        Set<String> keysBefore = avatarUploadObjectKeys();

        uploadAvatar(user, asUser(user), "photo.png", pdf)
                .andExpect(status().isUnsupportedMediaType());

        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(mediaCountUploadedBy(user)).isZero();
        assertNoNewAvatarUploadHolds(pdf, keysBefore);
    }

    @Test
    void oversizedImageIsRefusedSynchronouslyAndItsStoredUploadIsDeleted() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);
        byte[] bomb = pngHeaderOnly(50000, 50000);
        Set<String> keysBefore = avatarUploadObjectKeys();

        uploadAvatar(user, asUser(user), "photo.png", bomb)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Invalid image"));

        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(avatarUploadCountUploadedBy(user)).isZero();
        assertNoNewAvatarUploadHolds(bomb, keysBefore);
        assertThat(avatarStorageKey(user)).isEqualTo(key);
        assertThat(userJson(user).get("avatarPending").asBoolean()).isFalse();
    }

    @Test
    void imageWhoseHeaderPassesButCannotBeDecodedIsDroppedByTheWorker() throws Exception {
        User user = createUser(UserRole.USER);
        uploadOwnAvatar(user, opaquePng());
        String previousKey = avatarStorageKey(user);

        String uploadKey = uploadWhileWorkerIsPaused(user, pngHeaderOnly(100, 100));

        assertThat(uploadKey).isNotNull();

        awaitProcessed(user);

        JsonNode body = userJson(user);

        assertThat(body.get("avatarPending").asBoolean()).isFalse();
        assertThat(body.get("avatarUrl").asString()).contains(previousKey);
        assertThat(avatarStorageKey(user)).isEqualTo(previousKey);
        assertThat(headObject(previousKey).contentLength()).isPositive();
        assertThat(mediaRowCount(uploadKey)).isZero();
        awaitObjectMissing(uploadKey);
        assertThat(avatarUploadCountUploadedBy(user)).isZero();
    }

    @Test
    void firstUploadThatCannotBeDecodedLeavesTheUserWithoutPhoto() throws Exception {
        User user = createUser(UserRole.USER);

        uploadAvatar(user, asUser(user), "me.png", pngHeaderOnly(100, 100))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.avatarUrl").value(nullValue()));

        awaitProcessed(user);

        assertThat(avatarStorageKey(user)).isNull();
        assertThat(mediaCountUploadedBy(user)).isZero();
        assertThat(userJson(user).get("avatarPending").asBoolean()).isFalse();
    }

    @Test
    void processQueueIsDurableAndDeadLettersToTheDeadLetterQueue() {
        JsonNode queue = managementApi("/api/queues/%2F/" + AvatarQueues.PROCESS);

        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("auto_delete").asBoolean()).isFalse();
        assertThat(queue.path("exclusive").asBoolean()).isFalse();
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").isString()).isTrue();
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").asString()).isEmpty();
        assertThat(queue.path("arguments").path("x-dead-letter-routing-key").asString())
                .isEqualTo("avatar.process.dead-letter");
    }

    @Test
    void deadLetterQueueIsDurableAndDeadLettersNowhere() {
        JsonNode queue = managementApi("/api/queues/%2F/" + AvatarQueues.DEAD_LETTER);

        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("auto_delete").asBoolean()).isFalse();
        assertThat(queue.path("arguments").has("x-dead-letter-exchange")).isFalse();
        assertThat(queue.path("arguments").has("x-dead-letter-routing-key")).isFalse();
    }

    private void awaitQueueDrained() {
        await()
                .atMost(WORKER_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> amqpAdmin.getQueueInfo(AvatarQueues.PROCESS).getMessageCount() == 0);
    }

    private JsonNode managementApi(String path) {
        String body = RestClient.builder()
                .defaultHeaders(headers -> headers.setBasicAuth(
                        rabbitContainer.getAdminUsername(),
                        rabbitContainer.getAdminPassword()
                ))
                .build()
                .get()
                .uri(URI.create(rabbitContainer.getHttpUrl() + path))
                .retrieve()
                .body(String.class);

        return json(body);
    }

    private static boolean isBlue(int rgb) {
        Color color = new Color(rgb);
        return color.getBlue() > 200 && color.getRed() < 60 && color.getGreen() < 60;
    }
}
