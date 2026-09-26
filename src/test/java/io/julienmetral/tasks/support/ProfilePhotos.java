package io.julienmetral.tasks.support;

import io.julienmetral.tasks.media.model.MediaUsage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

// Media rows without stored objects: a response only presigns the photo's URL, so tests that count or shape
// responses need no upload, antivirus scan or avatar worker
public final class ProfilePhotos {

    private ProfilePhotos() {
    }

    public static void givePhoto(JdbcTemplate jdbcTemplate, UUID userId) {
        UUID mediaId = insertMedia(jdbcTemplate, userId, MediaUsage.AVATAR);

        jdbcTemplate.update("UPDATE user_profiles SET avatar_media_id = ? WHERE id = ?", mediaId, userId);
    }

    public static void givePendingPhoto(JdbcTemplate jdbcTemplate, UUID userId) {
        UUID mediaId = insertMedia(jdbcTemplate, userId, MediaUsage.AVATAR_UPLOAD);

        jdbcTemplate.update("UPDATE user_profiles SET pending_avatar_media_id = ? WHERE id = ?", mediaId, userId);
    }

    private static UUID insertMedia(JdbcTemplate jdbcTemplate, UUID uploaderId, MediaUsage usage) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           uploaded_by_id, created_at)
                        VALUES (?, ?, 'photo.jpg', 'image/jpeg', 1, repeat('0', 64), ?, now())
                        RETURNING id
                        """,
                UUID.class,
                usage.storagePrefix() + "/" + UUID.randomUUID(),
                usage.name(),
                uploaderId
        );
    }
}
