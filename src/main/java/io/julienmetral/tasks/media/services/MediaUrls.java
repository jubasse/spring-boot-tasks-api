package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.identity.controllers.IdenticonController;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.media.model.Media;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Presigned download URLs for response DTOs; signing is local, so one per user in a list costs no request. */
@Component
@RequiredArgsConstructor
public class MediaUrls {

    private final MediaService mediaService;

    /** Null when there is no media. */
    public String of(Media media) {
        return media == null ? null : mediaService.downloadUrl(media).url().toString();
    }

    /** The profile's photo while others may see it, otherwise its identicon, so a client always has an image. */
    public String avatarOf(UserProfile profile) {
        Media avatar = profile.visibleAvatar();

        return avatar == null ? IdenticonController.urlOf(profile.getId()) : of(avatar);
    }
}
