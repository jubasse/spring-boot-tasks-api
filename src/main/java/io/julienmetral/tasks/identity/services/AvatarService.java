package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Checks the photo (size, type, antivirus, then dimensions from its header) and stores it as uploaded, as the
     * user's pending photo. Publishes {@link AvatarUploaded}: the worker produces the square version after commit
     * (see {@link #process}). A newer upload replaces a pending one; the current photo stays until then.
     */
    @Transactional
    public User update(UUID userId, MultipartFile file) {
        User user = getUser(userId);

        Media upload = mediaService.store(file, MediaUsage.AVATAR_UPLOAD, currentUser.getId().orElse(null));

        // After the type check, so a PDF gets 415 rather than "unreadable image"; a rollback deletes the upload
        imageProcessor.checkDimensions(bytes(file));

        replacePendingAvatar(user, upload);

        eventPublisher.publishEvent(new AvatarUploaded(user.getId(), upload.getId()));

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
        User user = userRepository.findById(userId).orElse(null);

        if (user == null
                || user.getPendingAvatar() == null
                || !Objects.equals(user.getPendingAvatar().getId(), uploadId)) {
            return;
        }

        Media upload = user.getPendingAvatar();

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
        Media previous = user.getAvatar();

        user.setAvatar(avatar);

        if (previous != null) {
            mediaService.delete(previous);
        }
    }

    private void replacePendingAvatar(User user, Media upload) {
        Media previous = user.getPendingAvatar();

        user.setPendingAvatar(upload);

        if (previous != null) {
            mediaService.delete(previous);
        }
    }

    private User getUser(UUID userId) {
        return userRepository
                .findById(userId)
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
