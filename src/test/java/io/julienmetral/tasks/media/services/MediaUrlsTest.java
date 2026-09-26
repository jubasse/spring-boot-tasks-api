package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUrlsTest {

    private static final UUID PROFILE_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final String PRESIGNED_URL = "https://storage.example/avatar/key?X-Amz-Signature=abc";

    @Mock
    private MediaService mediaService;

    @InjectMocks
    private MediaUrls mediaUrls;

    private void presign(Media media) throws Exception {
        MediaDownload download = new MediaDownload(
                URI.create(PRESIGNED_URL).toURL(),
                Instant.parse("2026-01-01T00:10:00Z")
        );

        when(mediaService.downloadUrl(media)).thenReturn(download);
    }

    @Test
    void mediaGivesItsPresignedUrl() throws Exception {
        Media media = new Media();
        presign(media);

        assertThat(mediaUrls.of(media)).isEqualTo(PRESIGNED_URL);
    }

    @Test
    void noMediaGivesNullWithoutSigning() {
        assertThat(mediaUrls.of(null)).isNull();

        verifyNoInteractions(mediaService);
    }

    @Nested
    class AvatarOf {

        @AfterEach
        void clearRequest() {
            RequestContextHolder.resetRequestAttributes();
        }

        private UserProfile withAvatar(UserStatus status, Media avatar) {
            UserProfile profile = profile(PROFILE_ID, "Alice", status);
            profile.setAvatar(avatar);
            return profile;
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = {"ACTIVE", "UNVERIFIED"})
        void visiblePhotoGivesItsPresignedUrl(UserStatus status) throws Exception {
            Media avatar = new Media();
            presign(avatar);

            assertThat(mediaUrls.avatarOf(withAvatar(status, avatar))).isEqualTo(PRESIGNED_URL);
        }

        @Test
        void profileWithoutPhotoGivesItsIdenticonWithoutSigning() {
            assertThat(mediaUrls.avatarOf(withAvatar(UserStatus.ACTIVE, null)))
                    .isEqualTo("/api/v1/identicons/" + PROFILE_ID);

            verifyNoInteractions(mediaService);
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = {"DISABLED", "DELETED"})
        void hiddenPhotoGivesTheIdenticonWithoutSigning(UserStatus status) {
            assertThat(mediaUrls.avatarOf(withAvatar(status, new Media())))
                    .isEqualTo("/api/v1/identicons/" + PROFILE_ID);

            verifyNoInteractions(mediaService);
        }

        @Test
        void identiconUrlIsAbsoluteForTheCurrentRequest() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setScheme("https");
            request.setServerName("tasks.example");
            request.setServerPort(443);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            assertThat(mediaUrls.avatarOf(withAvatar(UserStatus.ACTIVE, null)))
                    .isEqualTo("https://tasks.example/api/v1/identicons/" + PROFILE_ID);
        }
    }
}
