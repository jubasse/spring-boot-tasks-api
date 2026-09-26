package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaSource;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.model.ProcessedImage;
import io.julienmetral.tasks.media.services.AvatarImageProcessor;
import io.julienmetral.tasks.media.services.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AvatarService {

    private final UserRepository userRepository;
    private final MediaService mediaService;
    private final AvatarImageProcessor imageProcessor;
    private final CurrentUser currentUser;

    /**
     * Validates the uploaded photo as sent, before decoding it, then stores a re-encoded square version. The previous
     * photo's row is deleted with the change, and its file once the transaction commits.
     */
    @Transactional
    public User update(UUID userId, MultipartFile file) {
        User user = getUser(userId);

        mediaService.validate(MediaSource.of(file), MediaUsage.AVATAR);

        ProcessedImage image = imageProcessor.process(bytes(file));

        Media avatar = mediaService.store(
                MediaSource.of(image.content(), "avatar." + image.extension()),
                MediaUsage.AVATAR,
                currentUser.getId().orElse(null)
        );

        replaceAvatar(user, avatar);

        return user;
    }

    @Transactional
    public User remove(UUID userId) {
        User user = getUser(userId);

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
