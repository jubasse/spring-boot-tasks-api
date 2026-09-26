package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.reference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AvatarService service;

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "holiday.jpg", "image/jpeg", ORIGINAL);
    }

    private User existingUser(Media avatar, Media pendingAvatar) {
        User user = new User();
        user.setId(USER_ID);
        user.setAvatar(avatar);
        user.setPendingAvatar(pendingAvatar);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private static Media media(String storageKey) {
        return media(UUID.randomUUID(), storageKey, null);
    }

    private static Media media(UUID id, String storageKey, UUID uploaderId) {
        Media media = new Media();
        media.setId(id);
        media.setStorageKey(storageKey);
        media.setUploadedBy(uploaderId == null ? null : reference(uploaderId));
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
        void storesTheUploadAsSentThenChecksItsDimensionsAndKeepsItPending() {
            Media current = media("avatar/current");
            User user = existingUser(current, null);
            Media stored = media(UPLOAD_ID, "avatar-upload/new", USER_ID);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, USER_ID)).thenReturn(stored);

            User updated = service.update(USER_ID, file);

            assertThat(updated).isSameAs(user);
            assertThat(user.getPendingAvatar()).isSameAs(stored);
            assertThat(user.getAvatar()).isSameAs(current);

            InOrder order = inOrder(mediaService, imageProcessor, eventPublisher);
            order.verify(mediaService).store(file, MediaUsage.AVATAR_UPLOAD, USER_ID);
            order.verify(imageProcessor).checkDimensions(ORIGINAL);
            order.verify(eventPublisher).publishEvent(new AvatarUploaded(USER_ID, UPLOAD_ID));
            verify(imageProcessor, never()).process(any());
            verify(mediaService, never()).delete(any());
        }

        @Test
        void uploaderIsTheActingUserEvenForSomeoneElsesPhoto() {
            existingUser(null, null);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(ADMIN_ID));
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, ADMIN_ID))
                    .thenReturn(media(UPLOAD_ID, "avatar-upload/new", ADMIN_ID));

            service.update(USER_ID, file);

            verify(mediaService).store(file, MediaUsage.AVATAR_UPLOAD, ADMIN_ID);
            verify(eventPublisher).publishEvent(new AvatarUploaded(USER_ID, UPLOAD_ID));
        }

        @Test
        void withoutAuthenticatedUserTheUploaderIsLeftEmpty() {
            existingUser(null, null);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.empty());
            when(mediaService.store(eq(file), eq(MediaUsage.AVATAR_UPLOAD), isNull()))
                    .thenReturn(media(UPLOAD_ID, "avatar-upload/new", null));

            service.update(USER_ID, file);

            verify(mediaService).store(eq(file), eq(MediaUsage.AVATAR_UPLOAD), isNull());
        }

        @Test
        void newerUploadReplacesAndDeletesThePendingOne() {
            Media pending = media(OTHER_UPLOAD_ID, "avatar-upload/pending", USER_ID);
            User user = existingUser(null, pending);
            Media stored = media(UPLOAD_ID, "avatar-upload/new", USER_ID);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, USER_ID)).thenReturn(stored);

            service.update(USER_ID, file);

            assertThat(user.getPendingAvatar()).isSameAs(stored);
            InOrder order = inOrder(mediaService);
            order.verify(mediaService).store(file, MediaUsage.AVATAR_UPLOAD, USER_ID);
            order.verify(mediaService).delete(pending);
            verify(eventPublisher).publishEvent(new AvatarUploaded(USER_ID, UPLOAD_ID));
        }

        @Test
        void currentPhotoIsKeptUntilTheWorkerReplacesIt() {
            Media current = media("avatar/current");
            User user = existingUser(current, null);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, USER_ID))
                    .thenReturn(media(UPLOAD_ID, "avatar-upload/new", USER_ID));

            service.update(USER_ID, file);

            assertThat(user.getAvatar()).isSameAs(current);
            verify(mediaService, never()).delete(current);
        }

        @Test
        void oversizedImageIsRejectedAfterStoringAndNothingIsPublished() {
            Media pending = media(OTHER_UPLOAD_ID, "avatar-upload/pending", USER_ID);
            User user = existingUser(null, pending);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, USER_ID))
                    .thenReturn(media(UPLOAD_ID, "avatar-upload/new", USER_ID));
            InvalidImageException failure = new InvalidImageException("50000x50000 is too large");
            doThrow(failure).when(imageProcessor).checkDimensions(ORIGINAL);

            assertThatThrownBy(() -> service.update(USER_ID, file)).isSameAs(failure);

            assertThat(user.getPendingAvatar()).isSameAs(pending);
            verify(mediaService, never()).delete(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void rejectedUploadIsNeitherCheckedNorPublished() {
            Media pending = media(OTHER_UPLOAD_ID, "avatar-upload/pending", USER_ID);
            User user = existingUser(null, pending);
            MockMultipartFile file = upload();
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));
            InfectedMediaException failure = new InfectedMediaException("Eicar-Test-Signature");
            when(mediaService.store(file, MediaUsage.AVATAR_UPLOAD, USER_ID)).thenThrow(failure);

            assertThatThrownBy(() -> service.update(USER_ID, file)).isSameAs(failure);

            verifyNoInteractions(imageProcessor, eventPublisher);
            verify(mediaService, never()).delete(any());
            assertThat(user.getPendingAvatar()).isSameAs(pending);
        }

        @Test
        void unknownUserIsRejectedBeforeReadingTheFile() {
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(USER_ID, upload()))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessage("User not found with id: " + USER_ID);

            verifyNoInteractions(mediaService, imageProcessor, eventPublisher);
        }

        @Test
        void unreadableUploadIsRethrownUncheckedAndNothingIsPublished() throws IOException {
            User user = existingUser(null, null);
            IOException failure = new IOException("gone");
            MultipartFile file = mock(MultipartFile.class);
            when(file.getBytes()).thenThrow(failure);
            when(currentUser.getId()).thenReturn(Optional.of(USER_ID));

            assertThatThrownBy(() -> service.update(USER_ID, file))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verifyNoInteractions(imageProcessor, eventPublisher);
            assertThat(user.getPendingAvatar()).isNull();
        }
    }

    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class Process {

        @Test
        void unknownUserIsIgnored() {
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

            assertThatCode(() -> service.process(USER_ID, UPLOAD_ID)).doesNotThrowAnyException();

            verifyNoInteractions(mediaService, imageProcessor);
        }

        @Test
        void userWithoutPendingUploadIsIgnored() {
            Media current = media("avatar/current");
            User user = existingUser(current, null);

            service.process(USER_ID, UPLOAD_ID);

            assertThat(user.getAvatar()).isSameAs(current);
            verifyNoInteractions(mediaService, imageProcessor);
        }

        @Test
        void staleUploadIdIsIgnored() {
            Media current = media("avatar/current");
            Media newer = media(OTHER_UPLOAD_ID, "avatar-upload/newer", USER_ID);
            User user = existingUser(current, newer);

            service.process(USER_ID, UPLOAD_ID);

            assertThat(user.getPendingAvatar()).isSameAs(newer);
            assertThat(user.getAvatar()).isSameAs(current);
            verifyNoInteractions(mediaService, imageProcessor);
        }

        @Test
        void reencodesThePendingUploadIntoTheProfilePhoto() throws IOException {
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            User user = existingUser(null, upload);
            Media stored = media("avatar/new");
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenReturn(stored);

            service.process(USER_ID, UPLOAD_ID);

            assertThat(user.getAvatar()).isSameAs(stored);
            assertThat(user.getPendingAvatar()).isNull();

            ArgumentCaptor<MediaSource> storedSource = ArgumentCaptor.forClass(MediaSource.class);
            verify(mediaService).store(storedSource.capture(), eq(MediaUsage.AVATAR), eq(USER_ID));
            assertThat(storedSource.getValue().filename()).isEqualTo("avatar.jpg");
            assertThat(storedSource.getValue().size()).isEqualTo(PROCESSED.length);
            assertThat(read(storedSource.getValue())).isEqualTo(PROCESSED);
            verify(mediaService).delete(upload);
            verify(imageProcessor, never()).checkDimensions(any());
        }

        @Test
        void transparentImageIsStoredAsPng() {
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            existingUser(null, upload);
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/png", "png"));

            service.process(USER_ID, UPLOAD_ID);

            ArgumentCaptor<MediaSource> storedSource = ArgumentCaptor.forClass(MediaSource.class);
            verify(mediaService).store(storedSource.capture(), eq(MediaUsage.AVATAR), eq(USER_ID));
            assertThat(storedSource.getValue().filename()).isEqualTo("avatar.png");
        }

        @Test
        void photoIsStoredWithTheUploaderOfTheUploadNotTheUser() {
            Media upload = media(UPLOAD_ID, "avatar-upload/key", ADMIN_ID);
            existingUser(null, upload);
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));

            service.process(USER_ID, UPLOAD_ID);

            verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(ADMIN_ID));
        }

        @Test
        void uploadWithoutUploaderGivesAPhotoWithoutUploader() {
            Media upload = media(UPLOAD_ID, "avatar-upload/key", null);
            existingUser(null, upload);
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));

            service.process(USER_ID, UPLOAD_ID);

            verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), isNull());
        }

        @Test
        void previousPhotoIsReplacedAndDeletedAfterTheNewOneIsStored() {
            Media previous = media("avatar/previous");
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            User user = existingUser(previous, upload);
            Media stored = media("avatar/new");
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenReturn(stored);

            service.process(USER_ID, UPLOAD_ID);

            assertThat(user.getAvatar()).isSameAs(stored);
            assertThat(user.getPendingAvatar()).isNull();
            InOrder order = inOrder(mediaService);
            order.verify(mediaService).store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID));
            order.verify(mediaService).delete(previous);
            verify(mediaService).delete(upload);
        }

        @Test
        void undecodableImageIsDroppedWithAWarningInsteadOfThrowing(CapturedOutput output) {
            Media previous = media("avatar/previous");
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            User user = existingUser(previous, upload);
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenThrow(new InvalidImageException("no pixel data"));

            assertThatCode(() -> service.process(USER_ID, UPLOAD_ID)).doesNotThrowAnyException();

            assertThat(user.getPendingAvatar()).isNull();
            assertThat(user.getAvatar()).isSameAs(previous);
            verify(mediaService).delete(upload);
            verify(mediaService, never()).delete(previous);
            verify(mediaService, never()).store(any(MediaSource.class), any(), any());
            assertThat(output).contains(
                    "Dropped the profile photo " + UPLOAD_ID + " of user " + USER_ID,
                    "no pixel data"
            );
        }

        @Test
        void storageFailureWhileReadingPropagatesSoTheMessageIsRetried() {
            Media previous = media("avatar/previous");
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            User user = existingUser(previous, upload);
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            when(mediaService.read(upload)).thenThrow(failure);

            assertThatThrownBy(() -> service.process(USER_ID, UPLOAD_ID)).isSameAs(failure);

            assertThat(user.getAvatar()).isSameAs(previous);
            verifyNoInteractions(imageProcessor);
            verify(mediaService, never()).store(any(MediaSource.class), any(), any());
            verify(mediaService, never()).delete(previous);
        }

        @Test
        void storageFailureWhileStoringPropagatesSoTheMessageIsRetried() {
            Media previous = media("avatar/previous");
            Media upload = media(UPLOAD_ID, "avatar-upload/key", USER_ID);
            User user = existingUser(previous, upload);
            when(mediaService.read(upload)).thenReturn(ORIGINAL);
            when(imageProcessor.process(ORIGINAL)).thenReturn(new ProcessedImage(PROCESSED, "image/jpeg", "jpg"));
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            when(mediaService.store(any(MediaSource.class), eq(MediaUsage.AVATAR), eq(USER_ID))).thenThrow(failure);

            assertThatThrownBy(() -> service.process(USER_ID, UPLOAD_ID)).isSameAs(failure);

            assertThat(user.getAvatar()).isSameAs(previous);
            verify(mediaService, never()).delete(previous);
        }
    }

    @Nested
    class Remove {

        @Test
        void clearsAndDeletesTheCurrentPhotoAndThePendingUpload() {
            Media previous = media("avatar/previous");
            Media pending = media(UPLOAD_ID, "avatar-upload/pending", USER_ID);
            User user = existingUser(previous, pending);

            User updated = service.remove(USER_ID);

            assertThat(updated).isSameAs(user);
            assertThat(user.getAvatar()).isNull();
            assertThat(user.getPendingAvatar()).isNull();
            verify(mediaService).delete(previous);
            verify(mediaService).delete(pending);
        }

        @Test
        void clearsAPendingUploadWithoutCurrentPhoto() {
            Media pending = media(UPLOAD_ID, "avatar-upload/pending", USER_ID);
            User user = existingUser(null, pending);

            service.remove(USER_ID);

            assertThat(user.getPendingAvatar()).isNull();
            verify(mediaService).delete(pending);
        }

        @Test
        void userWithoutPhotoIsLeftAsIs() {
            User user = existingUser(null, null);

            service.remove(USER_ID);

            assertThat(user.getAvatar()).isNull();
            assertThat(user.getPendingAvatar()).isNull();
            verifyNoInteractions(mediaService);
        }

        @Test
        void unknownUserIsRejected() {
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.remove(USER_ID)).isInstanceOf(UserNotFoundException.class);

            verifyNoInteractions(mediaService);
        }
    }
}
