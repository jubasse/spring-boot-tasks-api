package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaSource;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.model.ProcessedImage;
import io.julienmetral.tasks.media.services.AvatarImageProcessor;
import io.julienmetral.tasks.media.services.MediaService;
import io.julienmetral.tasks.messaging.services.Outbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final UserRepository userRepository;
    private final MediaService mediaService;
    private final AvatarImageProcessor imageProcessor;
    private final CurrentUser currentUser;
    private final Outbox outbox;

    /**
     * Checks the photo (size, type, antivirus, then dimensions from its header) and stores it as uploaded, as the
     * user's pending photo. Writes {@link AvatarUploaded} to the outbox: the worker produces the square version after
     * commit (see {@link #process}). A newer upload replaces a pending one; the current photo stays until then.
     */
    @Transactional
    public User update(UUID userId, MultipartFile file) {
        User user = getUser(userId);

        Media upload = mediaService.store(file, MediaUsage.AVATAR_UPLOAD, currentUser.getId().orElse(null));

        // After the type check, so a PDF gets 415 rather than "unreadable image"; a rollback deletes the upload
        imageProcessor.checkDimensions(bytes(file));

        replacePendingAvatar(user, upload);

        outbox.enqueue(AvatarQueues.PROCESS, new AvatarUploaded(user.getId(), upload.getId()));

        // The response shows the current photo, which stays until the worker replaces it
        ProfilesForDisplay.load(user.getProfile());

        return user;
    }

    /**
     * Run by the worker: re-encodes the pending upload into the profile photo, replaces the current one and deletes
     * the upload. Does nothing when the upload is no longer the user's pending one (replaced, removed, or the user
     * was deleted), so a redelivered or stale message is harmless. An image that cannot be decoded is dropped with a
     * warning instead of being retried.
     */
    @Transactional
    public void process(UUID userId, UUID uploadId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);

        if (user == null
                || user.getProfile().getPendingAvatar() == null
                || !Objects.equals(user.getProfile().getPendingAvatar().getId(), uploadId)) {
            return;
        }

        Media upload = user.getProfile().getPendingAvatar();

        replacePendingAvatar(user, null);

        ProcessedImage image;

        try {
            image = imageProcessor.process(mediaService.read(upload));
        } catch (InvalidImageException exception) {
            log.warn("Dropped the profile photo {} of user {}: {}", uploadId, userId, exception.getMessage());
            return;
        }

        Media avatar = mediaService.store(
                MediaSource.of(image.content(), "avatar." + image.extension()),
                MediaUsage.AVATAR,
                upload.getUploadedBy() == null ? null : upload.getUploadedBy().getId()
        );

        replaceAvatar(user, avatar);
    }

    /** Removes the current photo and any upload still waiting for the worker. */
    @Transactional
    public User remove(UUID userId) {
        User user = getUser(userId);

        replacePendingAvatar(user, null);
        replaceAvatar(user, null);

        return user;
    }

    private void replaceAvatar(User user, Media avatar) {
        Media previous = user.getProfile().getAvatar();

        user.getProfile().setAvatar(avatar);

        if (previous != null) {
            mediaService.delete(previous);
        }
    }

    private void replacePendingAvatar(User user, Media upload) {
        Media previous = user.getProfile().getPendingAvatar();

        user.getProfile().setPendingAvatar(upload);

        if (previous != null) {
            mediaService.delete(previous);
        }
    }

    // Every photo change locks the user row first: the upload request and the worker both update it and delete media
    // rows, and without a common first lock they deadlocked (Postgres 40P01) or read a pending upload already replaced
    private User getUser(UUID userId) {
        return userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
