package io.julienmetral.tasks.media.services;

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
}
