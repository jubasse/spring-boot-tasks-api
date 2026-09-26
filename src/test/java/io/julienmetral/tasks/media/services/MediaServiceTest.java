package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaProperties;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
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
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.reference;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final UUID UPLOADER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final byte[] CONTENT = "%PDF-1.4 report".getBytes(UTF_8);

    private static final DataSize AVATAR_MAX = DataSize.ofBytes(10);

    private static final DataSize ATTACHMENT_MAX = DataSize.ofBytes(20);

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private ContentTypeDetector contentTypeDetector;

    private MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(
                mediaRepository,
                userSummaryRepository,
                objectStorage,
                contentTypeDetector,
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
            UserSummary uploader = reference(UPLOADER_ID);
            when(userSummaryRepository.getReferenceById(UPLOADER_ID)).thenReturn(uploader);
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
            verifyNoInteractions(userSummaryRepository);
        }

        @Test
        void emptyFileIsRejectedBeforeAnythingElse() {
            assertThatThrownBy(() -> service.store(file("empty.pdf", new byte[0]), MediaUsage.TASK_ATTACHMENT,
                    UPLOADER_ID))
                    .isInstanceOf(EmptyMediaException.class)
                    .hasMessage("The file is empty");

            verifyNoInteractions(contentTypeDetector, objectStorage, mediaRepository, userSummaryRepository);
            assertThat(synchronizations()).isEmpty();
        }

        @Test
        void avatarAboveItsLimitIsRejectedWithoutReadingTheContent() {
            byte[] content = new byte[(int) AVATAR_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.store(file("me.png", content), MediaUsage.AVATAR, UPLOADER_ID))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, objectStorage, mediaRepository);
        }

        @Test
        void attachmentAboveItsLimitIsRejected() {
            byte[] content = new byte[(int) ATTACHMENT_MAX.toBytes() + 1];

            assertThatThrownBy(() -> service.store(file("a.pdf", content), MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(MediaTooLargeException.class);

            verifyNoInteractions(contentTypeDetector, objectStorage, mediaRepository);
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

            verifyNoInteractions(objectStorage, mediaRepository, userSummaryRepository);
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
            when(file.getInputStream()).thenReturn(InputStream.nullInputStream()).thenThrow(failure);
            detects("application/pdf");

            assertThatThrownBy(() -> service.store(file, MediaUsage.TASK_ATTACHMENT, null))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCause(failure);

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
