package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaProperties;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.model.MediaSource;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.repositories.MediaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.julienmetral.tasks.support.UserProfiles.reference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final UUID UPLOADER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final byte[] CONTENT = "%PDF-1.4 report".getBytes(UTF_8);

    private static final byte[] PHOTO = "photo".getBytes(UTF_8);

    private static final DataSize AVATAR_MAX = DataSize.ofBytes(10);

    private static final DataSize ATTACHMENT_MAX = DataSize.ofBytes(20);

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private ContentTypeDetector contentTypeDetector;

    // Mockito answers an unstubbed Optional with Optional.empty(): every file is clean unless a test says otherwise
    @Mock
    private VirusScanner virusScanner;

    private MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(
                mediaRepository,
                userProfileRepository,
                objectStorage,
                contentTypeDetector,
                virusScanner,
                new MediaProperties(AVATAR_MAX, ATTACHMENT_MAX)
        );
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static MockMultipartFile file(String originalFilename, byte[] content) {
        return new MockMultipartFile("file", originalFilename, "application/octet-stream", content);
    }

    private void detects(String contentType) throws IOException {
        when(contentTypeDetector.detect(any(InputStream.class), anyString())).thenReturn(contentType);
    }

    private void savesWhatItIsGiven() {
        when(mediaRepository.save(any(Media.class))).then(returnsFirstArg());
    }

    private String uploadedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(key.capture(), any(InputStream.class), anyLong(), anyString());
        return key.getValue();
    }

    private static List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }

    @Nested
    class Store {

        @Test
        void storesAttachmentUnderUsagePrefixWithDetectedTypeAndChecksum() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();
            UserProfile uploader = reference(UPLOADER_ID);
            when(userProfileRepository.getReferenceById(UPLOADER_ID)).thenReturn(uploader);
            byte[][] uploaded = new byte[1][];
            doAnswer(invocation -> {
                uploaded[0] = invocation.<InputStream>getArgument(1).readAllBytes();
                return null;
            }).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

            Instant before = Instant.now();
            Media media = service.store(file("report.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, UPLOADER_ID);

            String key = uploadedKey();
            assertThat(key).startsWith("task-attachment/");
            assertThat(UUID.fromString(key.substring("task-attachment/".length()))).isNotNull();
            verify(objectStorage).put(eq(key), any(InputStream.class), eq((long) CONTENT.length),
                    eq("application/pdf"));
            assertThat(uploaded[0]).isEqualTo(CONTENT);

            assertThat(media.getStorageKey()).isEqualTo(key);
            assertThat(media.getUsage()).isEqualTo(MediaUsage.TASK_ATTACHMENT);
            assertThat(media.getOriginalFilename()).isEqualTo("report.pdf");
            assertThat(media.getContentType()).isEqualTo("application/pdf");
            assertThat(media.getSizeBytes()).isEqualTo(CONTENT.length);
            assertThat(media.getSha256()).isEqualTo(sha256Hex(CONTENT));
            assertThat(media.getUploadedBy()).isSameAs(uploader);
            assertThat(media.getCreatedAt()).isBetween(before, Instant.now());
            assertThat(media.getId()).isNull();
            verify(mediaRepository).save(media);
        }

        @Test
        void storesAvatarUnderAvatarPrefix() throws IOException {
            detects("image/png");
            savesWhatItIsGiven();

            Media media = service.store(file("me.png", "png-bytes".getBytes(UTF_8)), MediaUsage.AVATAR, null);

            assertThat(uploadedKey()).startsWith("avatar/");
            assertThat(media.getUsage()).isEqualTo(MediaUsage.AVATAR);
        }

        @Test
        void storesAvatarUploadUnderItsOwnPrefix() throws IOException {
            detects("image/png");
            savesWhatItIsGiven();

            Media media = service.store(file("me.png", "png-bytes".getBytes(UTF_8)), MediaUsage.AVATAR_UPLOAD, null);

            assertThat(uploadedKey()).startsWith("avatar-upload/");
            assertThat(media.getUsage()).isEqualTo(MediaUsage.AVATAR_UPLOAD);
        }

        @Test
        void storageKeysAreUniquePerUpload() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();

            Media first = service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);
            Media second = service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(first.getStorageKey()).isNotEqualTo(second.getStorageKey());
        }

        @Test
        void checksumCoversTheWholeStreamWhenStorageReadsItInChunks() throws IOException {
            byte[] content = new byte[20];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) i;
            }
            detects("application/zip");
            savesWhatItIsGiven();
            doAnswer(invocation -> {
                InputStream stream = invocation.getArgument(1);
                byte[] buffer = new byte[3];
                while (stream.read(buffer) != -1) {
                    stream.read();
                }
                return null;
            }).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

            Media media = service.store(file("a.zip", content), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(media.getSha256()).isEqualTo(sha256Hex(content));
        }

        @Test
        void withoutUploaderLeavesUploadedByEmpty() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();

            Media media = service.store(file("report.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(media.getUploadedBy()).isNull();
            verifyNoInteractions(userProfileRepository);
        }

        @Test
        void emptyFileIsRejectedBeforeAnythingElse() {
            assertThatThrownBy(() -> service.store(file("empty.pdf", new byte[0]), MediaUsage.TASK_ATTACHMENT,
                    UPLOADER_ID))
                    .isInstanceOf(EmptyMediaException.class)
                    .hasMessage("The file is empty");

            verifyNoInteractions(contentTypeDetector, virusScanner, objectStorage, mediaRepository,
                    userProfileRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void avatarAboveItsLimitIsRejectedWithoutReadingTheContent() {
            byte[] content = new byte[(int) AVATAR_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.store(file("me.png", content), MediaUsage.AVATAR, UPLOADER_ID))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, virusScanner, objectStorage, mediaRepository);
        }

        @Test
        void avatarUploadAboveTheAvatarLimitIsRejectedWithoutReadingTheContent() {
            byte[] content = new byte[(int) AVATAR_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.store(file("me.png", content), MediaUsage.AVATAR_UPLOAD, UPLOADER_ID))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, virusScanner, objectStorage, mediaRepository);
        }

        @Test
        void pdfIsNotAcceptedAsAvatarUpload() throws IOException {
            detects("application/pdf");

            assertThatThrownBy(() -> service.store(file("cv.pdf", "%PDF-1.4".getBytes(UTF_8)),
                    MediaUsage.AVATAR_UPLOAD, UPLOADER_ID))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .hasMessage("Files of type application/pdf are not accepted for AVATAR_UPLOAD");

            verifyNoInteractions(virusScanner, objectStorage, mediaRepository);
        }

        @Test
        void attachmentAboveItsLimitIsRejected() {
            byte[] content = new byte[(int) ATTACHMENT_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.store(file("a.pdf", content), MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, virusScanner, objectStorage, mediaRepository);
        }

        @Test
        void fileExactlyAtTheLimitIsAccepted() throws IOException {
            detects("image/png");
            savesWhatItIsGiven();
            byte[] content = new byte[(int) AVATAR_MAX.toBytes()];
            content[0] = 1;

            Media media = service.store(file("me.png", content), MediaUsage.AVATAR, null);

            assertThat(media.getSizeBytes()).isEqualTo(AVATAR_MAX.toBytes());
        }

        @Test
        void attachmentLimitAppliesToAttachmentsNotTheAvatarLimit() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();
            byte[] content = new byte[(int) AVATAR_MAX.toBytes() + 1];
            content[0] = 1;

            Media media = service.store(file("a.pdf", content), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(media.getSizeBytes()).isEqualTo(content.length);
        }

        @Test
        void typeNotAllowedForUsageIsRejectedAndNothingIsUploaded() throws IOException {
            detects("application/pdf");

            assertThatThrownBy(() -> service.store(file("cv.pdf", "%PDF-1.4".getBytes(UTF_8)), MediaUsage.AVATAR,
                    UPLOADER_ID))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .hasMessage("Files of type application/pdf are not accepted for AVATAR");

            verifyNoInteractions(virusScanner, objectStorage, mediaRepository, userProfileRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void detectorSeesTheFileBytesAndTheSanitizedName() throws IOException {
            byte[][] seen = new byte[1][];
            when(contentTypeDetector.detect(any(InputStream.class), eq("report.pdf"))).thenAnswer(invocation -> {
                seen[0] = invocation.<InputStream>getArgument(0).readAllBytes();
                return "application/x-msdownload";
            });

            assertThatThrownBy(() -> service.store(file("../../report.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT,
                    null))
                    .isInstanceOf(UnsupportedMediaTypeException.class);

            assertThat(seen[0]).isEqualTo(CONTENT);
        }

        @Test
        void failingDetectionIsRethrownUnchecked() throws IOException {
            IOException failure = new IOException("disk");
            when(contentTypeDetector.detect(any(InputStream.class), anyString())).thenThrow(failure);

            assertThatThrownBy(() -> service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verifyNoInteractions(objectStorage, mediaRepository);
        }

        @Test
        void unreadableFileIsRethrownUnchecked() throws IOException {
            IOException failure = new IOException("gone");
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn(5L);
            when(file.getOriginalFilename()).thenReturn("a.pdf");
            when(file.getInputStream()).thenThrow(failure);

            assertThatThrownBy(() -> service.store(file, MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verifyNoInteractions(contentTypeDetector, objectStorage, mediaRepository);
        }

        @Test
        void fileUnreadableForUploadIsRethrownUncheckedAndNothingIsSaved() throws IOException {
            IOException failure = new IOException("gone");
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn(5L);
            when(file.getOriginalFilename()).thenReturn("a.pdf");
            when(file.getInputStream())
                    .thenReturn(InputStream.nullInputStream(), InputStream.nullInputStream())
                    .thenThrow(failure);
            detects("application/pdf");

            assertThatThrownBy(() -> service.store(file, MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verify(virusScanner).findThreat(any(InputStream.class));
            verifyNoInteractions(objectStorage, mediaRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void failedUploadRegistersNoCleanupAndSavesNothing() throws IOException {
            detects("application/pdf");
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            doThrow(failure).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

            assertThatThrownBy(() -> service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null))
                    .isSameAs(failure);

            verifyNoInteractions(mediaRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void missingSha256AlgorithmIsAnIllegalState() throws IOException {
            detects("application/pdf");
            NoSuchAlgorithmException failure = new NoSuchAlgorithmException("SHA-256");

            try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
                digests.when(() -> MessageDigest.getInstance("SHA-256")).thenThrow(failure);

                assertThatThrownBy(() -> service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null))
                        .isInstanceOf(IllegalStateException.class)
                        .hasCause(failure);
            }

            verifyNoInteractions(objectStorage, mediaRepository);
        }
    }

    @Nested
    class Scan {

        @Test
        void infectedFileIsRejectedWithTheThreatAndNothingIsStored() throws IOException {
            detects("application/pdf");
            when(virusScanner.findThreat(any(InputStream.class))).thenReturn(Optional.of("Eicar-Test-Signature"));

            assertThatThrownBy(() -> service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, UPLOADER_ID))
                    .isInstanceOf(InfectedMediaException.class)
                    .hasMessage("The file was rejected by the antivirus: Eicar-Test-Signature");

            verifyNoInteractions(objectStorage, mediaRepository, userProfileRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void unavailableAntivirusRejectsTheUploadAndNothingIsStored() throws IOException {
            detects("application/pdf");
            AntivirusUnavailableException failure = new AntivirusUnavailableException(new IOException("refused"));
            when(virusScanner.findThreat(any(InputStream.class))).thenThrow(failure);

            assertThatThrownBy(() -> service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, UPLOADER_ID))
                    .isSameAs(failure);

            verifyNoInteractions(objectStorage, mediaRepository, userProfileRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void scannerReceivesTheFileBytesOnce() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();
            byte[][] scanned = new byte[1][];
            when(virusScanner.findThreat(any(InputStream.class))).thenAnswer(invocation -> {
                scanned[0] = invocation.<InputStream>getArgument(0).readAllBytes();
                return Optional.empty();
            });

            service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(scanned[0]).isEqualTo(CONTENT);
            verify(virusScanner).findThreat(any(InputStream.class));
            verifyNoMoreInteractions(virusScanner);
        }

        @Test
        void cleanFileIsScannedBeforeItIsUploaded() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();
            AtomicBoolean scanned = new AtomicBoolean();
            when(virusScanner.findThreat(any(InputStream.class))).thenAnswer(invocation -> {
                scanned.set(true);
                return Optional.empty();
            });
            doAnswer(invocation -> {
                assertThat(scanned).isTrue();
                return null;
            }).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

            service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);

            verify(objectStorage).put(anyString(), any(InputStream.class), anyLong(), eq("application/pdf"));
        }

        @Test
        void scannedStreamIsClosedEvenWhenAThreatIsFound() throws IOException {
            AtomicBoolean closed = new AtomicBoolean();
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn((long) CONTENT.length);
            when(file.getOriginalFilename()).thenReturn("a.pdf");
            when(file.getInputStream()).then(invocation -> new ByteArrayInputStream(CONTENT) {
                @Override
                public void close() {
                    closed.set(true);
                }
            });
            detects("application/pdf");
            when(virusScanner.findThreat(any(InputStream.class))).thenAnswer(invocation -> {
                closed.set(false);
                return Optional.of("Eicar-Test-Signature");
            });

            assertThatThrownBy(() -> service.store(file, MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(InfectedMediaException.class);

            assertThat(closed).isTrue();
        }

        @Test
        void fileUnreadableForTheScanIsRethrownUncheckedAndNothingIsStored() throws IOException {
            IOException failure = new IOException("gone");
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn(5L);
            when(file.getOriginalFilename()).thenReturn("a.pdf");
            when(file.getInputStream()).thenReturn(InputStream.nullInputStream()).thenThrow(failure);
            detects("application/pdf");

            assertThatThrownBy(() -> service.store(file, MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

            verifyNoInteractions(virusScanner, objectStorage, mediaRepository);
            assertThat(synchronizations()).isEmpty();
        }
    }

    @Nested
    class OriginalFilename {

        private String storedFilename(MultipartFile file) throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();

            return service.store(file, MediaUsage.TASK_ATTACHMENT, null).getOriginalFilename();
        }

        private String storedFilename(String originalFilename) throws IOException {
            return storedFilename(file(originalFilename, CONTENT));
        }

        @Test
        void keepsAPlainName() throws IOException {
            assertThat(storedFilename("Quarterly report (final).pdf")).isEqualTo("Quarterly report (final).pdf");
        }

        @Test
        void keepsNonAsciiCharacters() throws IOException {
            assertThat(storedFilename("résumé été.pdf")).isEqualTo("résumé été.pdf");
        }

        @Test
        void dropsUnixPathSegments() throws IOException {
            assertThat(storedFilename("/home/jane/docs/report.pdf")).isEqualTo("report.pdf");
        }

        @Test
        void dropsWindowsPathSegments() throws IOException {
            assertThat(storedFilename("C:\\Users\\jane\\Documents\\report.pdf")).isEqualTo("report.pdf");
        }

        @Test
        void dropsTraversalSegments() throws IOException {
            assertThat(storedFilename("../../../etc/passwd")).isEqualTo("passwd");
            assertThat(storedFilename("..\\..\\boot.ini")).isEqualTo("boot.ini");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "docs/", "C:\\Users\\jane\\"})
        void fallsBackWhenNoNameIsLeft(String originalFilename) throws IOException {
            assertThat(storedFilename(originalFilename)).isEqualTo("file");
        }

        @ParameterizedTest
        @NullSource
        void fallsBackWhenTheClientSendsNoName(String originalFilename) throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.getSize()).thenReturn((long) CONTENT.length);
            when(file.getOriginalFilename()).thenReturn(originalFilename);
            when(file.getInputStream()).then(invocation -> new ByteArrayInputStream(CONTENT));

            assertThat(storedFilename(file)).isEqualTo("file");
        }

        @Test
        void keepsTheLast255CharactersOfALongName() throws IOException {
            String name = "a".repeat(300) + ".pdf";

            String stored = storedFilename(name);

            assertThat(stored).hasSize(255).endsWith(".pdf").isEqualTo(name.substring(name.length() - 255));
        }

        @Test
        void keepsANameOfExactly255Characters() throws IOException {
            String name = "b".repeat(251) + ".pdf";

            assertThat(storedFilename(name)).isEqualTo(name);
        }
    }

    @Nested
    class RollbackCleanup {

        private TransactionSynchronization storeAndGetCleanup() throws IOException {
            detects("application/pdf");
            savesWhatItIsGiven();

            service.store(file("a.pdf", CONTENT), MediaUsage.TASK_ATTACHMENT, null);

            assertThat(synchronizations()).hasSize(1);
            return synchronizations().getFirst();
        }

        @Test
        void rollbackDeletesTheUploadedObject() throws IOException {
            TransactionSynchronization cleanup = storeAndGetCleanup();

            cleanup.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            String key = uploadedKey();
            verify(objectStorage).delete(key);
        }

        @Test
        void unknownOutcomeDeletesTheUploadedObject() throws IOException {
            TransactionSynchronization cleanup = storeAndGetCleanup();

            cleanup.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

            String key = uploadedKey();
            verify(objectStorage).delete(key);
        }

        @Test
        void commitKeepsTheUploadedObject() throws IOException {
            TransactionSynchronization cleanup = storeAndGetCleanup();

            cleanup.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(objectStorage, never()).delete(anyString());
        }

        @Test
        void failingDeleteAfterRollbackIsSwallowed() throws IOException {
            TransactionSynchronization cleanup = storeAndGetCleanup();
            doThrow(new StorageUnavailableException(new RuntimeException("down")))
                    .when(objectStorage).delete(anyString());

            cleanup.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            String key = uploadedKey();
            verify(objectStorage).delete(key);
        }
    }

    @Nested
    class Validate {

        @Test
        void cleanAllowedContentGivesTheDetectedType() throws IOException {
            detects("image/png");

            String contentType = service.validate(MediaSource.of(PHOTO, "me.png"), MediaUsage.AVATAR);

            assertThat(contentType).isEqualTo("image/png");
            verify(virusScanner).findThreat(any(InputStream.class));
        }

        @Test
        void validContentIsNeitherUploadedNorSaved() throws IOException {
            detects("image/png");

            service.validate(MediaSource.of(PHOTO, "me.png"), MediaUsage.AVATAR);

            verifyNoInteractions(objectStorage, mediaRepository, userProfileRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void emptyContentIsRejectedBeforeDetectionAndScan() {
            assertThatThrownBy(() -> service.validate(MediaSource.of(new byte[0], "me.png"), MediaUsage.AVATAR))
                    .isInstanceOf(EmptyMediaException.class);

            verifyNoInteractions(contentTypeDetector, virusScanner);
        }

        @Test
        void contentAboveTheUsageLimitIsRejectedBeforeDetectionAndScan() {
            byte[] content = new byte[(int) AVATAR_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.validate(MediaSource.of(content, "me.png"), MediaUsage.AVATAR))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, virusScanner);
        }

        @Test
        void typeNotAllowedForTheUsageIsRejectedBeforeTheScan() throws IOException {
            detects("application/pdf");

            assertThatThrownBy(() -> service.validate(MediaSource.of(PHOTO, "me.png"), MediaUsage.AVATAR))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .hasMessage("Files of type application/pdf are not accepted for AVATAR");

            verifyNoInteractions(virusScanner);
        }

        @Test
        void detectorSeesTheSanitizedName() throws IOException {
            when(contentTypeDetector.detect(any(InputStream.class), eq("me.png"))).thenReturn("image/png");

            assertThat(service.validate(MediaSource.of(PHOTO, "../../me.png"), MediaUsage.AVATAR))
                    .isEqualTo("image/png");
        }

        @Test
        void detectorSeesTheFallbackNameWhenTheSourceHasNone() throws IOException {
            when(contentTypeDetector.detect(any(InputStream.class), eq("file"))).thenReturn("image/png");

            assertThat(service.validate(MediaSource.of(PHOTO, null), MediaUsage.AVATAR)).isEqualTo("image/png");
        }

        @Test
        void infectedContentIsRejectedWithTheThreat() throws IOException {
            detects("image/png");
            when(virusScanner.findThreat(any(InputStream.class))).thenReturn(Optional.of("Eicar-Test-Signature"));

            assertThatThrownBy(() -> service.validate(MediaSource.of(PHOTO, "me.png"), MediaUsage.AVATAR))
                    .isInstanceOf(InfectedMediaException.class)
                    .hasMessage("The file was rejected by the antivirus: Eicar-Test-Signature");

            verifyNoInteractions(objectStorage, mediaRepository);
        }

        @Test
        void scannerReceivesTheWholeContent() throws IOException {
            detects("image/png");
            byte[][] scanned = new byte[1][];
            when(virusScanner.findThreat(any(InputStream.class))).thenAnswer(invocation -> {
                scanned[0] = invocation.<InputStream>getArgument(0).readAllBytes();
                return Optional.empty();
            });

            service.validate(MediaSource.of(PHOTO, "me.png"), MediaUsage.AVATAR);

            assertThat(scanned[0]).isEqualTo(PHOTO);
        }
    }

    @Nested
    class StoreFromBytes {

        @Test
        void storesTheBytesUnderTheGivenNameWithTheirSizeAndChecksum() throws IOException {
            detects("image/jpeg");
            savesWhatItIsGiven();
            byte[][] uploaded = new byte[1][];
            doAnswer(invocation -> {
                uploaded[0] = invocation.<InputStream>getArgument(1).readAllBytes();
                return null;
            }).when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

            Media media = service.store(MediaSource.of(PHOTO, "avatar.jpg"), MediaUsage.AVATAR, null);

            assertThat(uploadedKey()).startsWith("avatar/");
            assertThat(uploaded[0]).isEqualTo(PHOTO);
            assertThat(media.getOriginalFilename()).isEqualTo("avatar.jpg");
            assertThat(media.getContentType()).isEqualTo("image/jpeg");
            assertThat(media.getSizeBytes()).isEqualTo(PHOTO.length);
            assertThat(media.getSha256()).isEqualTo(sha256Hex(PHOTO));
        }

        @Test
        void scansOnceBeforeUploading() throws IOException {
            detects("image/jpeg");
            savesWhatItIsGiven();

            service.store(MediaSource.of(PHOTO, "avatar.jpg"), MediaUsage.AVATAR, null);

            InOrder order = inOrder(virusScanner, objectStorage);
            order.verify(virusScanner).findThreat(any(InputStream.class));
            order.verify(objectStorage).put(anyString(), any(InputStream.class), anyLong(), eq("image/jpeg"));
            verifyNoMoreInteractions(virusScanner);
        }
    }

    @Nested
    class Delete {

        private static final String KEY = "avatar/previous";

        private Media media() {
            Media media = new Media();
            media.setStorageKey(KEY);
            return media;
        }

        @Test
        void deletesTheRowAtOnceAndTheObjectOnlyAfterCommit() {
            Media media = media();

            service.delete(media);

            verify(mediaRepository).delete(media);
            verifyNoInteractions(objectStorage);
            assertThat(synchronizations()).hasSize(1);

            synchronizations().getFirst().afterCommit();

            verify(objectStorage).delete(KEY);
        }

        @Test
        void rollbackKeepsTheObject() {
            service.delete(media());
            TransactionSynchronization cleanup = synchronizations().getFirst();

            cleanup.beforeCompletion();
            cleanup.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verifyNoInteractions(objectStorage);
        }

        @Test
        void deletesTheKeyTheMediaHadWhenDeleted() {
            Media media = media();
            service.delete(media);
            media.setStorageKey("avatar/changed-later");

            synchronizations().getFirst().afterCommit();

            verify(objectStorage).delete(KEY);
        }

        @Test
        void failingObjectDeletionAfterCommitIsSwallowed() {
            service.delete(media());
            doThrow(new StorageUnavailableException(new RuntimeException("down"))).when(objectStorage).delete(KEY);

            synchronizations().getFirst().afterCommit();

            verify(objectStorage).delete(KEY);
        }

        @Test
        void failingRowDeletionRegistersNoObjectDeletion() {
            Media media = media();
            RuntimeException failure = new RuntimeException("constraint");
            doThrow(failure).when(mediaRepository).delete(media);

            assertThatThrownBy(() -> service.delete(media)).isSameAs(failure);

            assertThat(synchronizations()).isEmpty();
        }
    }

    @Nested
    class Read {

        private static final String KEY = "avatar-upload/key";

        private Media media() {
            Media media = new Media();
            media.setStorageKey(KEY);
            return media;
        }

        @Test
        void returnsTheWholeStoredFile() {
            when(objectStorage.open(KEY)).thenReturn(new ByteArrayInputStream(CONTENT));

            assertThat(service.read(media())).isEqualTo(CONTENT);
        }

        @Test
        void closesTheStoredStream() {
            AtomicBoolean closed = new AtomicBoolean();
            when(objectStorage.open(KEY)).thenReturn(new ByteArrayInputStream(PHOTO) {
                @Override
                public void close() {
                    closed.set(true);
                }
            });

            service.read(media());

            assertThat(closed).isTrue();
        }

        @Test
        void failureWhileReadingIsRethrownUnchecked() {
            IOException failure = new IOException("connection reset");
            when(objectStorage.open(KEY)).thenReturn(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw failure;
                }
            });

            assertThatThrownBy(() -> service.read(media()))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);
        }

        @Test
        void unavailableStoragePropagates() {
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            when(objectStorage.open(KEY)).thenThrow(failure);

            assertThatThrownBy(() -> service.read(media())).isSameAs(failure);
        }

        @Test
        void neitherTouchesTheRowNorRegistersAnyCleanup() {
            when(objectStorage.open(KEY)).thenReturn(new ByteArrayInputStream(CONTENT));

            service.read(media());

            verifyNoInteractions(mediaRepository);
            assertThat(synchronizations()).isEmpty();
        }
    }

    @Test
    void downloadUrlPresignsTheStoredKeyWithOriginalNameAndType() throws Exception {
        Media media = new Media();
        media.setStorageKey("task-attachment/key");
        media.setOriginalFilename("report.pdf");
        media.setContentType("application/pdf");
        MediaDownload download = new MediaDownload(URI.create("https://storage.example/x").toURL(),
                Instant.parse("2026-01-01T00:10:00Z"));
        when(objectStorage.presignDownload("task-attachment/key", "report.pdf", "application/pdf"))
                .thenReturn(download);

        assertThat(service.downloadUrl(media)).isSameAs(download);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
