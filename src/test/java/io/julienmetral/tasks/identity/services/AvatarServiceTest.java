package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaSource;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.model.ProcessedImage;
import io.julienmetral.tasks.media.services.AvatarImageProcessor;
import io.julienmetral.tasks.media.services.MediaService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final byte[] ORIGINAL = "original-photo-with-exif".getBytes(UTF_8);
    private static final byte[] PROCESSED = "processed-square".getBytes(UTF_8);

    @Mock
    private UserRepository userRepository;

    @Mock
    private MediaService mediaService;

    @Mock
    private AvatarImageProcessor imageProcessor;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private AvatarService service;

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "holiday.jpg", "image/jpeg", ORIGINAL);
    }

    private User existingUser(Media avatar) {
        User user = new User();
        user.setId(USER_ID);
        user.setAvatar(avatar);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private static Media media(String storageKey) {
        Media media = new Media();
        media.setStorageKey(storageKey);
        return media;
    }

    private static byte[] read(MediaSource source) throws IOException {
        try (InputStream content = source.open()) {
            return content.readAllBytes();
        }
    }

    @Nested
    class Update {

        @Test
        void validatesTheOriginalThenStoresTheProcessedImageAndSetsIt() throws IOException {
            User user = existingUser(null);
            Media stored = media("avatar/new");
            when(imageProcessor.process(ORIGINAL))
                    .thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenReturn(stored);

            User updated = service.update(USER_ID, upload());

            assertThat(updated).isSameAs(user);
            assertThat(user.getAvatar()).isSameAs(stored);

            ArgumentCaptor<MediaSource> validated = ArgumentCaptor.forClass(MediaSource.class);
            ArgumentCaptor<MediaSource> storedSource = ArgumentCaptor.forClass(MediaSource.class);
            InOrder order = inOrder(mediaService, imageProcessor);
            order.verify(mediaService).validate(validated.capture(), eq(MediaUsage.AVATAR));
            order.verify(imageProcessor).process(ORIGINAL);
            order.verify(mediaService).store(storedSource.capture(), eq(MediaUsage.AVATAR), eq(USER_ID));

            assertThat(validated.getValue().filename()).isEqualTo("holiday.jpg");
            assertThat(read(validated.getValue())).isEqualTo(ORIGINAL);
            assertThat(storedSource.getValue().filename()).isEqualTo("avatar.jpg");
            assertThat(storedSource.getValue().size()).isEqualTo(PROCESSED.length);
            assertThat(read(storedSource.getValue())).isEqualTo(PROCESSED);
            verify(mediaService, never()).delete(any());
        }

        @Test
        void transparentImageIsStoredAsPng() {
            existingUser(null);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/png", "png"));
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));

            service.update(USER_ID, upload());

            ArgumentCaptor<MediaSource> storedSource = ArgumentCaptor.forClass(MediaSource.class);
            verify(mediaService).store(storedSource.capture(), eq(MediaUsage.AVATAR), eq(USER_ID));
            assertThat(storedSource.getValue().filename()).isEqualTo("avatar.png");
        }

        @Test
        void uploaderIsTheActingUserEvenForSomeoneElsesPhoto() {
            existingUser(null);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(currentUser.getId()).thenReturn(Optional.of(ADMIN_ID));

            service.update(USER_ID, upload());

            verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(ADMIN_ID));
        }

        @Test
        void withoutAuthenticatedUserTheUploaderIsLeftEmpty() {
            existingUser(null);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(currentUser.getId()).thenReturn(Optional.empty());

            service.update(USER_ID, upload());

            verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), isNull());
        }

        @Test
        void previousAvatarIsReplacedAndDeleted() {
            Media previous = media("avatar/previous");
            User user = existingUser(previous);
            Media stored = media("avatar/new");
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenReturn(stored);

            service.update(USER_ID, upload());

            assertThat(user.getAvatar()).isSameAs(stored);
            InOrder order = inOrder(mediaService);
            order.verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID));
            order.verify(mediaService).delete(previous);
        }

        @Test
        void unknownUserIsRejectedBeforeReadingTheFile() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(USER_ID, upload()))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessage("User not found with id: " + USER_ID);

            verifyNoInteractions(mediaService, imageProcessor);
        }

        @Test
        void rejectedOriginalIsNeitherProcessedNorStored() {
            Media previous = media("avatar/previous");
            User user = existingUser(previous);
            InfectedMediaException failure = new InfectedMediaException("Eicar-Test-Signature");
            doThrow(failure).when(mediaService).validate(any(MediaSource.class), eq(MediaUsage.AVATAR));

            assertThatThrownBy(() -> service.update(USER_ID, upload())).isSameAs(failure);

            verifyNoInteractions(imageProcessor);
            verify(mediaService, never()).store(any(MediaSource.class), any(), any());
            verify(mediaService, never()).delete(any());
            assertThat(user.getAvatar()).isSameAs(previous);
        }

        @Test
        void unusableImageIsNotStoredAndThePreviousAvatarIsKept() {
            Media previous = media("avatar/previous");
            User user = existingUser(previous);
            InvalidImageException failure = new InvalidImageException("unreadable image");
            when(imageProcessor.process(ORIGINAL)).thenThrow(failure);

            assertThatThrownBy(() -> service.update(USER_ID, upload())).isSameAs(failure);

            verify(mediaService, never()).store(any(MediaSource.class), any(), any());
            verify(mediaService, never()).delete(any());
            assertThat(user.getAvatar()).isSameAs(previous);
        }

        @Test
        void failedStorageKeepsThePreviousAvatar() {
            Media previous = media("avatar/previous");
            User user = existingUser(previous);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenThrow(failure);

            assertThatThrownBy(() -> service.update(USER_ID, upload())).isSameAs(failure);

            verify(mediaService, never()).delete(any());
            assertThat(user.getAvatar()).isSameAs(previous);
        }

        @Test
        void unreadableUploadIsRethrownUncheckedAndNothingIsStored() throws IOException {
            existingUser(null);
            IOException failure = new IOException("gone");
            MultipartFile file = mock(MultipartFile.class);
            when(file.getBytes()).thenThrow(failure);

            assertThatThrownBy(() -> service.update(USER_ID, file))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verifyNoInteractions(imageProcessor);
            verify(mediaService, never()).store(any(MediaSource.class), any(), any());
        }
    }

    @Nested
    class Remove {

        @Test
        void clearsAndDeletesTheCurrentAvatar() {
            Media previous = media("avatar/previous");
            User user = existingUser(previous);

            User updated = service.remove(USER_ID);

            assertThat(updated).isSameAs(user);
            assertThat(user.getAvatar()).isNull();
            verify(mediaService).delete(previous);
        }

        @Test
        void userWithoutAvatarIsLeftAsIs() {
            User user = existingUser(null);

            service.remove(USER_ID);

            assertThat(user.getAvatar()).isNull();
            verifyNoInteractions(mediaService);
        }

        @Test
        void unknownUserIsRejected() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.remove(USER_ID)).isInstanceOf(UserNotFoundException.class);

            verifyNoInteractions(mediaService);
        }
    }
}
