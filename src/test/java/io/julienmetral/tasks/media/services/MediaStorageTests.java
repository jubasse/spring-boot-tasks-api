package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.repositories.MediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MediaStorageTests {

    private static final long AVATAR_MAX_BYTES = 5L * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private MediaService mediaService;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void storedAvatarIsSavedUploadedAndDownloadable() throws Exception {
        User uploader = createUser();
        byte[] png = uniquePng();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Media stored = mediaService.store(
                new MockMultipartFile("file", "avatar.png", "image/png", png),
                MediaUsage.AVATAR,
                uploader.getId()
        );

        Instant after = Instant.now();
        Media reloaded = reload(stored.getId());

        assertThat(reloaded.getUsage()).isEqualTo(MediaUsage.AVATAR);
        assertThat(reloaded.getOriginalFilename()).isEqualTo("avatar.png");
        assertThat(reloaded.getContentType()).isEqualTo("image/png");
        assertThat(reloaded.getSizeBytes()).isEqualTo(png.length);
        assertThat(reloaded.getSha256()).isEqualTo(sha256(png));
        assertThat(reloaded.getStorageKey()).matches("avatar/[0-9a-f-]{36}");
        assertThat(reloaded.getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(reloaded.getUploadedBy().getDisplayName()).isEqualTo(uploader.getDisplayName());
        assertThat(reloaded.getCreatedAt()).isBetween(before, after);

        HeadObjectResponse head = headObject(reloaded.getStorageKey());
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.contentLength()).isEqualTo(png.length);
        assertThat(objectBytes(reloaded.getStorageKey())).isEqualTo(png);

        HttpResponse<byte[]> download = download(mediaService.downloadUrl(reloaded));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(png);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow()))
                .satisfies(disposition -> {
                    assertThat(disposition.isAttachment()).isTrue();
                    assertThat(disposition.getFilename()).isEqualTo("avatar.png");
                });
    }

    @Test
    void storedAttachmentIsDownloadedUnderItsNonAsciiFilename() throws Exception {
        User uploader = createUser();
        byte[] pdf = uniquePdf();

        Media stored = mediaService.store(
                new MockMultipartFile("file", "Été rapport.pdf", "application/pdf", pdf),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        Media reloaded = reload(stored.getId());

        assertThat(reloaded.getUsage()).isEqualTo(MediaUsage.TASK_ATTACHMENT);
        assertThat(reloaded.getOriginalFilename()).isEqualTo("Été rapport.pdf");
        assertThat(reloaded.getContentType()).isEqualTo("application/pdf");
        assertThat(reloaded.getSizeBytes()).isEqualTo(pdf.length);
        assertThat(reloaded.getSha256()).isEqualTo(sha256(pdf));
        assertThat(reloaded.getStorageKey()).matches("task-attachment/[0-9a-f-]{36}");
        assertThat(reloaded.getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(objectBytes(reloaded.getStorageKey())).isEqualTo(pdf);

        MediaDownload link = mediaService.downloadUrl(reloaded);
        assertThat(link.expiresAt()).isAfter(Instant.now());

        HttpResponse<byte[]> download = download(link);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow()))
                .satisfies(disposition -> {
                    assertThat(disposition.isAttachment()).isTrue();
                    assertThat(disposition.getFilename()).isEqualTo("Été rapport.pdf");
                });
    }

    @Test
    void declaredContentTypeIsIgnoredInFavourOfTheDetectedOne() {
        User uploader = createUser();
        byte[] png = uniquePng();

        Media stored = mediaService.store(
                new MockMultipartFile("file", "photo", "application/octet-stream", png),
                MediaUsage.AVATAR,
                uploader.getId()
        );

        assertThat(stored.getContentType()).isEqualTo("image/png");
        assertThat(headObject(stored.getStorageKey()).contentType()).isEqualTo("image/png");
    }

    @Test
    void executableDisguisedAsPdfIsRejectedAndNothingIsStored() {
        User uploader = createUser();
        byte[] executable = uniqueExecutable();
        Set<String> keysBefore = objectKeys(MediaUsage.TASK_ATTACHMENT);

        assertThatThrownBy(() -> mediaService.store(
                new MockMultipartFile("file", "invoice.pdf", "application/pdf", executable),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        ))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessageNotContaining("application/pdf");

        assertThat(mediaCountUploadedBy(uploader)).isZero();
        assertNoNewObjectHolds(executable, MediaUsage.TASK_ATTACHMENT, keysBefore);
    }

    @Test
    void avatarJustAboveTheSizeLimitIsRejectedAndNothingIsStored() {
        User uploader = createUser();
        byte[] png = pngOfSize(AVATAR_MAX_BYTES + 1);
        Set<String> keysBefore = objectKeys(MediaUsage.AVATAR);

        assertThatThrownBy(() -> mediaService.store(
                new MockMultipartFile("file", "large.png", "image/png", png),
                MediaUsage.AVATAR,
                uploader.getId()
        )).isInstanceOf(MediaTooLargeException.class);

        assertThat(mediaCountUploadedBy(uploader)).isZero();
        assertNoNewObjectHolds(png, MediaUsage.AVATAR, keysBefore);
    }

    @Test
    void fileAboveTheAvatarLimitIsAcceptedAsAttachment() {
        User uploader = createUser();
        byte[] png = pngOfSize(AVATAR_MAX_BYTES + 1);

        Media stored = mediaService.store(
                new MockMultipartFile("file", "large.png", "image/png", png),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        assertThat(stored.getSizeBytes()).isEqualTo(png.length);
        assertThat(stored.getSha256()).isEqualTo(sha256(png));
        assertThat(headObject(stored.getStorageKey()).contentLength()).isEqualTo(png.length);
    }

    @Test
    void emptyFileIsRejected() {
        User uploader = createUser();

        assertThatThrownBy(() -> mediaService.store(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        )).isInstanceOf(EmptyMediaException.class);

        assertThat(mediaCountUploadedBy(uploader)).isZero();
    }

    @Test
    void rollbackOfTheCallerTransactionRemovesTheRowAndTheObject() {
        User uploader = createUser();
        byte[] pdf = uniquePdf();

        Media stored = transactionTemplate.execute(status -> {
            Media media = mediaService.store(
                    new MockMultipartFile("file", "draft.pdf", "application/pdf", pdf),
                    MediaUsage.TASK_ATTACHMENT,
                    uploader.getId()
            );

            assertThat(objectBytes(media.getStorageKey())).isEqualTo(pdf);
            status.setRollbackOnly();
            return media;
        });

        assertThat(mediaRepository.findById(stored.getId())).isEmpty();
        assertObjectMissing(stored.getStorageKey());
    }

    @Test
    void exceptionInTheCallerTransactionRemovesTheRowAndTheObject() {
        User uploader = createUser();
        byte[] pdf = uniquePdf();
        AtomicReference<Media> stored = new AtomicReference<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            stored.set(mediaService.store(
                    new MockMultipartFile("file", "draft.pdf", "application/pdf", pdf),
                    MediaUsage.TASK_ATTACHMENT,
                    uploader.getId()
            ));

            throw new IllegalStateException("Caller failed after the upload");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(stored.get()).isNotNull();
        assertThat(mediaRepository.findById(stored.get().getId())).isEmpty();
        assertObjectMissing(stored.get().getStorageKey());
    }

    @Test
    void mediaOfSoftDeletedUploaderStillLoadsItsUploader() {
        User uploader = createUser();

        Media stored = mediaService.store(
                new MockMultipartFile("file", "notes.pdf", "application/pdf", uniquePdf()),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        userService.delete(uploader.getId());

        Media reloaded = reload(stored.getId());

        assertThat(reloaded.getUploadedBy()).isNotNull();
        assertThat(reloaded.getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(reloaded.getUploadedBy().getDisplayName()).isEqualTo(uploader.getDisplayName());
        assertThat(reloaded.getUploadedBy().getStatus()).isEqualTo(UserStatus.DELETED);
    }

    @Test
    void storingTheSameBytesTwiceUsesDistinctKeys() {
        User uploader = createUser();
        byte[] pdf = uniquePdf();

        Media first = mediaService.store(
                new MockMultipartFile("file", "same.pdf", "application/pdf", pdf),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );
        Media second = mediaService.store(
                new MockMultipartFile("file", "same.pdf", "application/pdf", pdf),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        assertThat(second.getStorageKey()).isNotEqualTo(first.getStorageKey());
        assertThat(second.getSha256()).isEqualTo(first.getSha256());
        assertThat(objectBytes(first.getStorageKey())).isEqualTo(pdf);
        assertThat(objectBytes(second.getStorageKey())).isEqualTo(pdf);
    }

    private User createUser() {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Uploader " + UUID.randomUUID().toString().substring(0, 8));
        user.setRoles(EnumSet.of(UserRole.USER));

        return userRepository.saveAndFlush(user);
    }

    private Media reload(UUID mediaId) {
        return transactionTemplate.execute(status -> {
            Media media = mediaRepository.findById(mediaId).orElseThrow();
            // Initializes the lazy uploader while the session is open
            media.getUploadedBy().getDisplayName();
            return media;
        });
    }

    private int mediaCountUploadedBy(User uploader) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where uploaded_by_id = ?",
                Integer.class,
                uploader.getId()
        );
    }

    private HeadObjectResponse headObject(String key) {
        return s3Client.headObject(request -> request.bucket(storageProperties.bucket()).key(key));
    }

    private byte[] objectBytes(String key) {
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                request -> request.bucket(storageProperties.bucket()).key(key)
        );
        return object.asByteArray();
    }

    private void assertObjectMissing(String key) {
        assertThatThrownBy(() -> headObject(key)).isInstanceOf(NoSuchKeyException.class);
    }

    private Set<String> objectKeys(MediaUsage usage) {
        return s3Client
                .listObjectsV2Paginator(request -> request
                        .bucket(storageProperties.bucket())
                        .prefix(usage.storagePrefix() + "/"))
                .contents()
                .stream()
                .map(S3Object::key)
                .collect(Collectors.toSet());
    }

    // Other test classes share the bucket, so a new key alone proves nothing: compare contents instead
    private void assertNoNewObjectHolds(byte[] content, MediaUsage usage, Set<String> keysBefore) {
        objectKeys(usage).stream()
                .filter(key -> !keysBefore.contains(key))
                .forEach(key -> assertThat(objectBytes(key)).isNotEqualTo(content));
    }

    private HttpResponse<byte[]> download(MediaDownload link) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(link.url().toURI()).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] uniquePng() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt(0x1000000));
        image.setRGB(3, 3, ThreadLocalRandom.current().nextInt(0x1000000));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Detection only reads the leading bytes, so a PNG signature followed by random padding is typed as a PNG
    private static byte[] pngOfSize(long size) {
        byte[] content = new byte[Math.toIntExact(size)];
        ThreadLocalRandom.current().nextBytes(content);
        System.arraycopy(PNG_SIGNATURE, 0, content, 0, PNG_SIGNATURE.length);
        return content;
    }

    private static byte[] uniquePdf() {
        return """
                %%PDF-1.4
                %% %s
                1 0 obj << /Type /Catalog >> endobj
                trailer << /Root 1 0 R >>
                %%%%EOF
                """.formatted(UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    // DOS header ("MZ") of a Windows executable
    private static byte[] uniqueExecutable() {
        byte[] content = new byte[512];
        ThreadLocalRandom.current().nextBytes(content);
        content[0] = 'M';
        content[1] = 'Z';
        Arrays.fill(content, 2, 60, (byte) 0);
        content[60] = (byte) 0x80;
        content[61] = 0;
        content[62] = 0;
        content[63] = 0;
        content[0x80] = 'P';
        content[0x81] = 'E';
        content[0x82] = 0;
        content[0x83] = 0;
        return content;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
