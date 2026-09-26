package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUrlsTest {

    @Mock
    private MediaService mediaService;

    @InjectMocks
    private MediaUrls mediaUrls;

    @Test
    void mediaGivesItsPresignedUrl() throws Exception {
        Media media = new Media();
        String url = "https://storage.example/avatar/key?X-Amz-Signature=abc";
        when(mediaService.downloadUrl(media))
                .thenReturn(new MediaDownload(URI.create(url).toURL(), Instant.parse("2026-01-01T00:10:00Z")));

        assertThat(mediaUrls.of(media)).isEqualTo(url);
    }

    @Test
    void noMediaGivesNullWithoutSigning() {
        assertThat(mediaUrls.of(null)).isNull();

        verifyNoInteractions(mediaService);
    }
}
