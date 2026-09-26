package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AntivirusScanningTests {

    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.US_ASCII);

    private static final String EICAR_THREAT = TestcontainersConfiguration.EICAR_THREAT;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private VirusScanner virusScanner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextScansWithClamAv() {
        assertThat(virusScanner).isInstanceOf(ClamAvScanner.class);
    }

    @Test
    void eicarTextAttachmentIsRejectedWithTheThreatAndNothingIsStored() {
        User uploader = createUser();
        Set<String> keysBefore = objectKeys(MediaUsage.TASK_ATTACHMENT);

        assertThatThrownBy(() -> mediaService.store(
                new MockMultipartFile("file", "notes.txt", "text/plain", EICAR),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        ))
                .isInstanceOf(InfectedMediaException.class)
                .hasMessage("The file was rejected by the antivirus: " + EICAR_THREAT);

        assertThat(mediaCountUploadedBy(uploader)).isZero();
        assertNoNewObjectHolds(EICAR, keysBefore);
    }

    @Test
    void eicarInsideAZipAttachmentIsRejected() {
        User uploader = createUser();
        byte[] zip = zip("docs/readme.txt", uniqueText(), "docs/eicar.com", EICAR);
        Set<String> keysBefore = objectKeys(MediaUsage.TASK_ATTACHMENT);

        assertThatThrownBy(() -> mediaService.store(
                new MockMultipartFile("file", "docs.zip", "application/zip", zip),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        ))
                .isInstanceOf(InfectedMediaException.class)
                .hasMessage("The file was rejected by the antivirus: " + EICAR_THREAT);

        assertThat(mediaCountUploadedBy(uploader)).isZero();
        assertNoNewObjectHolds(zip, keysBefore);
    }

    @Test
    void cleanTextAttachmentIsStored() {
        User uploader = createUser();
        byte[] text = uniqueText();

        Media stored = mediaService.store(
                new MockMultipartFile("file", "notes.txt", "text/plain", text),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        assertThat(stored.getContentType()).isEqualTo("text/plain");
        assertThat(mediaCountUploadedBy(uploader)).isOne();
        assertThat(objectBytes(stored.getStorageKey())).isEqualTo(text);
    }

    @Test
    void cleanZipAttachmentIsStored() {
        User uploader = createUser();
        byte[] zip = zip("a.txt", uniqueText(), "b.txt", uniqueText());

        Media stored = mediaService.store(
                new MockMultipartFile("file", "docs.zip", "application/zip", zip),
                MediaUsage.TASK_ATTACHMENT,
                uploader.getId()
        );

        assertThat(stored.getContentType()).isEqualTo("application/zip");
        assertThat(objectBytes(stored.getStorageKey())).isEqualTo(zip);
    }

    @Test
    void cleanPngAvatarIsStored() {
        User uploader = createUser();
        byte[] png = uniquePng();

        Media stored = mediaService.store(
                new MockMultipartFile("file", "me.png", "image/png", png),
                MediaUsage.AVATAR,
                uploader.getId()
        );

        assertThat(stored.getContentType()).isEqualTo("image/png");
        assertThat(mediaCountUploadedBy(uploader)).isOne();
        assertThat(objectBytes(stored.getStorageKey())).isEqualTo(png);
    }

    @Test
    void cleanContentLargerThanOneChunkIsScannedAsClean() {
        byte[] content = new byte[3 * 64 * 1024 + 17];
        ThreadLocalRandom.current().nextBytes(content);

        assertThat(virusScanner.findThreat(new ByteArrayInputStream(content))).isEmpty();
    }

    @Test
    void eicarBeyondTheFirstChunkIsFound() {
        byte[] incompressible = new byte[3 * 64 * 1024];
        ThreadLocalRandom.current().nextBytes(incompressible);
        byte[] zip = zip("padding.bin", incompressible, "eicar.com", EICAR);

        assertThat(zip.length).isGreaterThan(3 * 64 * 1024);
        assertThat(virusScanner.findThreat(new ByteArrayInputStream(zip))).contains(EICAR_THREAT);
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

    private int mediaCountUploadedBy(User uploader) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where uploaded_by_id = ?",
                Integer.class,
                uploader.getId()
        );
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
    private void assertNoNewObjectHolds(byte[] content, Set<String> keysBefore) {
        objectKeys(MediaUsage.TASK_ATTACHMENT).stream()
                .filter(key -> !keysBefore.contains(key))
                .forEach(key -> assertThat(objectBytes(key)).isNotEqualTo(content));
    }

    private byte[] objectBytes(String key) {
        return s3Client.getObjectAsBytes(request -> request.bucket(storageProperties.bucket()).key(key))
                .asByteArray();
    }

    private static byte[] uniqueText() {
        return ("Meeting notes " + UUID.randomUUID() + "\nNothing to see here.\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String firstName, byte[] firstContent, String secondName, byte[] secondContent) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstContent);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondContent);
            zip.closeEntry();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }

        return output.toByteArray();
    }

    private static byte[] uniquePng() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt(0x1000000));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
