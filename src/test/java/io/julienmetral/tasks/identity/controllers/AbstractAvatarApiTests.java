package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the profile photo API tests: active users created in the database, uploads through the API,
 * and direct access to the stored objects and their {@code media} rows.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractAvatarApiTests {

    static final String USERS = "/api/v1/users";

    static final String AVATAR = USERS + "/{id}/avatar";

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

    /** Uploads a photo for the user as themselves and returns the {@code avatarUrl} of the response. */
    protected String uploadOwnAvatar(User user, byte[] png) throws Exception {
        String body = uploadAvatar(user, asUser(user), "me.png", png)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body).get("avatarUrl").asString();
    }

    protected JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    /** Storage key of the user's current avatar, or null without one. */
    protected String avatarStorageKey(User user) {
        List<String> keys = jdbcTemplate.queryForList(
                "select m.storage_key from users u join media m on m.id = u.avatar_media_id where u.id = ?",
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

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
