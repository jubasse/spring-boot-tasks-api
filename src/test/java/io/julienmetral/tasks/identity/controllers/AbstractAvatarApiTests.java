package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.model.MediaUsage;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the profile photo API tests: active users created in the database, uploads through the API
 * processed by the RabbitMQ worker, and direct access to the stored objects and their {@code media} rows.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractAvatarApiTests {

    static final String USERS = "/api/v1/users";

    static final String AVATAR = USERS + "/{id}/avatar";

    static final Duration WORKER_TIMEOUT = Duration.ofSeconds(20);

    static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected S3Client s3Client;

    @Autowired
    protected StorageProperties storageProperties;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected RabbitListenerEndpointRegistry listenerRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    protected User createUser(UserRole role) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Avatar " + role + " " + UUID.randomUUID().toString().substring(0, 4));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    protected RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    protected RequestPostProcessor asUser(User user) {
        return as(user, UserRole.USER);
    }

    protected RequestPostProcessor asAdmin(User admin) {
        return as(admin, UserRole.ADMIN);
    }

    protected MockMultipartHttpServletRequestBuilder avatarUpload(UUID userId, String filename, byte[] content) {
        return multipart(HttpMethod.PUT, AVATAR, userId)
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content));
    }

    protected ResultActions uploadAvatar(User target, RequestPostProcessor caller, String filename, byte[] content)
            throws Exception {
        return mockMvc.perform(avatarUpload(target.getId(), filename, content).with(caller));
    }

    /** Uploads a photo for the user as themselves, waits for the worker and returns the resulting avatar URL. */
    protected String uploadOwnAvatar(User user, byte[] image) throws Exception {
        return uploadAndAwaitAvatar(user, asUser(user), "me.png", image);
    }

    protected String uploadAndAwaitAvatar(User target, RequestPostProcessor caller, String filename, byte[] content)
            throws Exception {
        uploadAvatar(target, caller, filename, content)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.avatarPending").value(true));

        awaitProcessed(target);

        return userJson(target).get("avatarUrl").asString();
    }

    /**
     * Uploads with the worker stopped, so the pending upload can be observed before it is processed, and returns
     * its storage key. The worker is restarted before returning and then consumes the message.
     */
    protected String uploadWhileWorkerIsPaused(User user, byte[] image) throws Exception {
        MessageListenerContainer worker = avatarWorker();

        worker.stop();
        try {
            uploadAvatar(user, asUser(user), "me.png", image)
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.avatarPending").value(true));

            return pendingAvatarStorageKey(user);
        } finally {
            worker.start();
        }
    }

    protected MessageListenerContainer avatarWorker() {
        return listenerRegistry
                .getListenerContainers()
                .stream()
                .filter(container -> container instanceof AbstractMessageListenerContainer listener
                        && List.of(listener.getQueueNames()).contains(AvatarQueues.PROCESS))
                .findFirst()
                .orElseThrow();
    }

    // The worker runs after the upload's commit, on a listener thread
    protected void awaitProcessed(User user) {
        await()
                .atMost(WORKER_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> pendingAvatarStorageKey(user) == null);
    }

    protected JsonNode userJson(User user) throws Exception {
        String body = mockMvc.perform(get(USERS + "/{id}", user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body);
    }

    protected JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    /** The identicon URL as MockMvc requests see it: their default host is {@code http://localhost}. */
    protected static String identiconUrl(User user) {
        return "http://localhost/api/v1/identicons/" + user.getId();
    }

    /** Storage key of the user's current avatar, or null without one. */
    protected String avatarStorageKey(User user) {
        List<String> keys = jdbcTemplate.queryForList(
                "select m.storage_key from user_profiles p join media m on m.id = p.avatar_media_id where p.id = ?",
                String.class,
                user.getId()
        );

        return keys.isEmpty() ? null : keys.getFirst();
    }

    /** Storage key of the upload waiting for the worker, or null without one. */
    protected String pendingAvatarStorageKey(User user) {
        List<String> keys = jdbcTemplate.queryForList(
                """
                        select m.storage_key
                        from user_profiles p join media m on m.id = p.pending_avatar_media_id
                        where p.id = ?
                        """,
                String.class,
                user.getId()
        );

        return keys.isEmpty() ? null : keys.getFirst();
    }

    protected int mediaRowCount(String storageKey) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where storage_key = ?",
                Integer.class,
                storageKey
        );
    }

    protected int mediaCountUploadedBy(User user) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where uploaded_by_id = ?",
                Integer.class,
                user.getId()
        );
    }

    protected int avatarUploadCountUploadedBy(User user) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where uploaded_by_id = ? and usage = 'AVATAR_UPLOAD'",
                Integer.class,
                user.getId()
        );
    }

    protected HeadObjectResponse headObject(String key) {
        return s3Client.headObject(request -> request.bucket(storageProperties.bucket()).key(key));
    }

    protected byte[] objectBytes(String key) {
        return s3Client
                .getObjectAsBytes(request -> request.bucket(storageProperties.bucket()).key(key))
                .asByteArray();
    }

    protected void assertObjectMissing(String key) {
        assertThatThrownBy(() -> headObject(key)).isInstanceOf(NoSuchKeyException.class);
    }

    // The worker deletes objects once its transaction has committed, just after the row change becomes visible
    protected void awaitObjectMissing(String key) {
        await()
                .atMost(WORKER_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertObjectMissing(key));
    }

    protected Set<String> avatarUploadObjectKeys() {
        return s3Client
                .listObjectsV2Paginator(request -> request
                        .bucket(storageProperties.bucket())
                        .prefix(MediaUsage.AVATAR_UPLOAD.storagePrefix() + "/"))
                .contents()
                .stream()
                .map(S3Object::key)
                .collect(Collectors.toSet());
    }

    // Other test classes share the bucket, so a new key alone proves nothing: compare contents instead
    protected void assertNoNewAvatarUploadHolds(byte[] content, Set<String> keysBefore) {
        avatarUploadObjectKeys().stream()
                .filter(key -> !keysBefore.contains(key))
                .forEach(key -> assertThat(objectBytes(key)).isNotEqualTo(content));
    }

    protected HttpResponse<byte[]> download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    protected static BufferedImage filled(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();

        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();

        return image;
    }

    protected static byte[] png(BufferedImage image) {
        return encode(image, "png");
    }

    protected static byte[] jpeg(BufferedImage image) {
        return encode(image, "jpeg");
    }

    protected static byte[] opaquePng() {
        return png(filled(64, 64, Color.ORANGE));
    }

    protected static BufferedImage decode(byte[] image) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));

            assertThat(decoded).isNotNull();

            return decoded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A valid PNG signature and IHDR chunk declaring the given size, with no pixel data at all. */
    protected static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0);

        return concat(PNG_SIGNATURE, chunk("IHDR", ihdr.array()), chunk("IEND", new byte[0]));
    }

    protected static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);

        for (byte[] part : parts) {
            buffer.put(part);
        }

        return buffer.array();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);

        return ByteBuffer.allocate(12 + data.length)
                .putInt(data.length)
                .put(typeBytes)
                .put(data)
                .putInt((int) crc.getValue())
                .array();
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
