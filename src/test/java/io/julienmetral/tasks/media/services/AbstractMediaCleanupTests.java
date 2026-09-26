package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

abstract class AbstractMediaCleanupTests {

    static final Duration BEYOND_RETENTION = Duration.ofDays(31);

    static final Duration WITHIN_RETENTION = Duration.ofDays(29);

    static final Duration BEYOND_GRACE_PERIOD = Duration.ofDays(2);

    @Autowired
    MediaCleanupService cleanupService;

    @Autowired
    MediaService mediaService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    S3Client s3Client;

    @Autowired
    StorageProperties storageProperties;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    User createUser() {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Cleanup " + UUID.randomUUID().toString().substring(0, 8));
        user.setRoles(EnumSet.of(UserRole.USER));

        return userRepository.saveAndFlush(user);
    }

    UUID createTask(User creator) {
        return jdbcTemplate.queryForObject(
                """
                        insert into tasks (created_at, updated_at, priority, reference, status, title, version, created_by_id)
                        values (now(), now(), 'MEDIUM', ?, 'TO_DO', 'Cleanup task', 0, ?)
                        returning id
                        """,
                UUID.class,
                "CLN-" + UUID.randomUUID().toString().substring(0, 20),
                creator.getId()
        );
    }

    Media storeAttachment(User uploader) {
        return mediaService.store(
                new MockMultipartFile("file", "notes.pdf", "application/pdf", uniquePdf()),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );
    }

    Media storeAvatar(User uploader) {
        return mediaService.store(
                new MockMultipartFile("file", "avatar.png", "image/png", uniquePng()),
                MediaUsage.AVATAR,
                uploader.getId()
        );
    }

    Media storeAvatarUpload(User uploader) {
        return mediaService.store(
                new MockMultipartFile("file", "photo.png", "image/png", uniquePng()),
                MediaUsage.AVATAR_UPLOAD,
                uploader.getId()
        );
    }

    Media attachedToTask(UUID taskId, User uploader) {
        Media media = storeAttachment(uploader);

        jdbcTemplate.update(
                "insert into task_attachments (task_id, media_id, created_at) values (?, ?, now())",
                taskId,
                media.getId()
        );

        return media;
    }

    UUID createComment(UUID taskId, User author) {
        return jdbcTemplate.queryForObject(
                "insert into task_comments (task_id, author_id, body, created_at) values (?, ?, 'See file', now()) "
                        + "returning id",
                UUID.class,
                taskId,
                author.getId()
        );
    }

    Media attachedToComment(UUID taskId, UUID commentId, User uploader) {
        Media media = storeAttachment(uploader);

        jdbcTemplate.update(
                "insert into task_attachments (task_id, comment_id, media_id, created_at) values (?, ?, ?, now())",
                taskId,
                commentId,
                media.getId()
        );

        return media;
    }

    Media avatarOf(User user) {
        Media media = storeAvatar(user);

        jdbcTemplate.update("update user_profiles set avatar_media_id = ? where id = ?", media.getId(), user.getId());

        return media;
    }

    Media pendingUploadOf(User user) {
        Media media = storeAvatarUpload(user);

        jdbcTemplate.update(
                "update user_profiles set pending_avatar_media_id = ? where id = ?",
                media.getId(),
                user.getId()
        );

        return media;
    }

    void softDeleteTask(UUID taskId, Duration ago) {
        jdbcTemplate.update("update tasks set deleted_at = ? where id = ?", ago(ago), taskId);
    }

    void softDeleteUser(User user, Duration ago) {
        jdbcTemplate.update("update users set deleted_at = ? where id = ?", ago(ago), user.getId());
        jdbcTemplate.update("update user_profiles set status = 'DELETED' where id = ?", user.getId());
    }

    void backdateMedia(Media media, Duration ago) {
        jdbcTemplate.update("update media set created_at = ? where id = ?", ago(ago), media.getId());
    }

    String putObjectWithoutMediaRow() {
        String key = MediaUsage.TASK_ATTACHMENT.storagePrefix() + "/" + UUID.randomUUID();

        s3Client.putObject(
                request -> request.bucket(storageProperties.bucket()).key(key).contentType("application/pdf"),
                RequestBody.fromBytes(uniquePdf())
        );

        return key;
    }

    boolean mediaExists(Media media) {
        return jdbcTemplate.queryForObject(
                "select exists (select 1 from media where id = ?)",
                Boolean.class,
                media.getId()
        );
    }

    boolean isAttached(Media media) {
        return jdbcTemplate.queryForObject(
                "select exists (select 1 from task_attachments where media_id = ?)",
                Boolean.class,
                media.getId()
        );
    }

    UUID avatarMediaIdOf(User user) {
        return jdbcTemplate.queryForObject(
                "select avatar_media_id from user_profiles where id = ?",
                UUID.class,
                user.getId()
        );
    }

    UUID pendingAvatarMediaIdOf(User user) {
        return jdbcTemplate.queryForObject(
                "select pending_avatar_media_id from user_profiles where id = ?",
                UUID.class,
                user.getId()
        );
    }

    boolean objectExists(String key) {
        try {
            s3Client.headObject(request -> request.bucket(storageProperties.bucket()).key(key));
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        }
    }

    private Timestamp ago(Duration ago) {
        return Timestamp.from(clock.instant().minus(ago));
    }

    private static byte[] uniquePdf() {
        return """
                %%PDF-1.4
                %% %s
                1 0 obj << /Type /Catalog >> endobj
                trailer << /Root 1 0 R >>
                %%%%EOF
                """.formatted(UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] uniquePng() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt(0x1000000));
        image.setRGB(3, 3, ThreadLocalRandom.current().nextInt(0x1000000));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
