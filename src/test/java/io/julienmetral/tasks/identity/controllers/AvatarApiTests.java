package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AvatarApiTests extends AbstractAvatarApiTests {

    private static final int AVATAR_MAX_BYTES = 5 * 1024 * 1024;

    @Test
    void userUploadsOwnAvatar() throws Exception {
        User user = createUser(UserRole.USER);

        uploadAvatar(user, asUser(user), "me.png", opaquePng())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.avatarPending").value(true))
                .andExpect(jsonPath("$.avatarUrl").value(nullValue()));

        awaitProcessed(user);

        String key = avatarStorageKey(user);
        JsonNode body = userJson(user);

        assertThat(key).matches("avatar/[0-9a-f-]{36}");
        assertThat(body.get("avatarUrl").asString()).contains(key);
        assertThat(body.get("avatarPending").asBoolean()).isFalse();
        assertThat(headObject(key).contentType()).isEqualTo("image/jpeg");
        assertThat(mediaCountUploadedBy(user)).isOne();
    }

    @Test
    void adminUploadsAvatarOfAnotherUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);

        uploadAvatar(user, asAdmin(admin), "photo.png", opaquePng())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.avatarPending").value(true));

        awaitProcessed(user);

        String key = avatarStorageKey(user);

        assertThat(key).isNotNull();
        assertThat(mediaCountUploadedBy(admin)).isOne();
        assertThat(avatarUploadCountUploadedBy(admin)).isZero();
        assertThat(avatarStorageKey(admin)).isNull();
    }

    @Test
    void userCannotUploadAvatarOfAnotherUser() throws Exception {
        User caller = createUser(UserRole.USER);
        User target = createUser(UserRole.USER);

        uploadAvatar(target, asUser(caller), "photo.png", opaquePng())
                .andExpect(status().isForbidden());

        assertThat(avatarStorageKey(target)).isNull();
        assertThat(mediaCountUploadedBy(caller)).isZero();
    }

    @Test
    void uploadWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(avatarUpload(user.getId(), "photo.png", opaquePng()))
                .andExpect(status().isUnauthorized());

        assertThat(avatarStorageKey(user)).isNull();
    }

    @Test
    void uploadForUnknownUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(avatarUpload(UUID.randomUUID(), "photo.png", opaquePng()).with(asAdmin(admin)))
                .andExpect(status().isNotFound());

        assertThat(mediaCountUploadedBy(admin)).isZero();
    }

    @Test
    void uploadForSoftDeletedUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);

        mockMvc.perform(delete(USERS + "/{id}", user.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        uploadAvatar(user, asAdmin(admin), "photo.png", opaquePng())
                .andExpect(status().isNotFound());

        assertThat(mediaCountUploadedBy(admin)).isZero();
    }

    @Test
    void pdfUploadReturnsUnsupportedMediaType() throws Exception {
        User user = createUser(UserRole.USER);
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);

        uploadAvatar(user, asUser(user), "photo.png", pdf)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported file type"));

        assertThat(avatarStorageKey(user)).isNull();
        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(mediaCountUploadedBy(user)).isZero();
    }

    @Test
    void uploadJustAboveFiveMegabytesReturnsContentTooLarge() throws Exception {
        User user = createUser(UserRole.USER);
        byte[] oversized = Arrays.copyOf(opaquePng(), AVATAR_MAX_BYTES + 1);

        uploadAvatar(user, asUser(user), "photo.png", oversized)
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.title").value("File too large"));

        assertThat(mediaCountUploadedBy(user)).isZero();
    }

    @Test
    void emptyUploadReturnsBadRequest() throws Exception {
        User user = createUser(UserRole.USER);

        uploadAvatar(user, asUser(user), "photo.png", new byte[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Empty file"));

        assertThat(mediaCountUploadedBy(user)).isZero();
    }

    @Test
    void corruptImageReturnsUnprocessable() throws Exception {
        User user = createUser(UserRole.USER);
        byte[] garbage = new byte[2048];
        ThreadLocalRandom.current().nextBytes(garbage);
        byte[] corrupt = concat(PNG_SIGNATURE, garbage);

        uploadAvatar(user, asUser(user), "photo.png", corrupt)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Invalid image"));

        assertThat(avatarStorageKey(user)).isNull();
        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(mediaCountUploadedBy(user)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "50000, 50000",
            "10001, 10",
            "7000, 7000"
    })
    void imageDeclaringTooManyPixelsReturnsUnprocessable(int width, int height) throws Exception {
        User user = createUser(UserRole.USER);
        byte[] bomb = pngHeaderOnly(width, height);

        assertThat(bomb.length).isLessThan(100);

        uploadAvatar(user, asUser(user), "photo.png", bomb)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Invalid image"));

        assertThat(mediaCountUploadedBy(user)).isZero();
        assertThat(pendingAvatarStorageKey(user)).isNull();
    }

    @Test
    void replacingAvatarDeletesPreviousObjectAndRow() throws Exception {
        User user = createUser(UserRole.USER);

        uploadOwnAvatar(user, opaquePng());
        String previousKey = avatarStorageKey(user);

        String newUrl = uploadOwnAvatar(user, opaquePng());
        String newKey = avatarStorageKey(user);

        assertThat(newKey).isNotEqualTo(previousKey);
        assertThat(newUrl).contains(newKey);
        awaitObjectMissing(previousKey);
        assertThat(mediaRowCount(previousKey)).isZero();
        assertThat(headObject(newKey).contentLength()).isPositive();
        assertThat(mediaCountUploadedBy(user)).isOne();
    }

    @Test
    void rejectedReplacementKeepsCurrentAvatar() throws Exception {
        User user = createUser(UserRole.USER);

        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);

        uploadAvatar(user, asUser(user), "photo.png", pngHeaderOnly(50000, 50000))
                .andExpect(status().isUnprocessableContent());

        assertThat(avatarStorageKey(user)).isEqualTo(key);
        assertThat(pendingAvatarStorageKey(user)).isNull();
        assertThat(headObject(key).contentLength()).isPositive();
    }

    @Test
    void userRemovesOwnAvatar() throws Exception {
        User user = createUser(UserRole.USER);

        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);

        mockMvc.perform(delete(AVATAR, user.getId()).with(asUser(user)))
                .andExpect(status().isNoContent());

        assertThat(avatarStorageKey(user)).isNull();
        assertThat(mediaRowCount(key)).isZero();
        assertObjectMissing(key);

        mockMvc.perform(get(USERS + "/{id}", user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(nullValue()));
    }

    @Test
    void adminRemovesAvatarOfAnotherUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);

        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);

        mockMvc.perform(delete(AVATAR, user.getId()).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        assertThat(avatarStorageKey(user)).isNull();
        assertObjectMissing(key);
    }

    @Test
    void removingWithoutAvatarReturnsNoContent() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(delete(AVATAR, user.getId()).with(asUser(user)))
                .andExpect(status().isNoContent());

        assertThat(avatarStorageKey(user)).isNull();
    }

    @Test
    void userCannotRemoveAvatarOfAnotherUser() throws Exception {
        User caller = createUser(UserRole.USER);
        User target = createUser(UserRole.USER);

        uploadOwnAvatar(target, opaquePng());
        String key = avatarStorageKey(target);

        mockMvc.perform(delete(AVATAR, target.getId()).with(asUser(caller)))
                .andExpect(status().isForbidden());

        assertThat(avatarStorageKey(target)).isEqualTo(key);
        assertThat(headObject(key).contentLength()).isPositive();
    }

    @Test
    void removeWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(delete(AVATAR, user.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeForUnknownUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(delete(AVATAR, UUID.randomUUID()).with(asAdmin(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void userResponseCarriesDownloadableAvatarUrl() throws Exception {
        User user = createUser(UserRole.USER);

        uploadOwnAvatar(user, opaquePng());
        String key = avatarStorageKey(user);

        String body = mockMvc.perform(get(USERS + "/{id}", user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String avatarUrl = json(body).get("avatarUrl").asString();

        assertThat(avatarUrl).contains(key);
        assertThat(download(avatarUrl).body()).isEqualTo(objectBytes(key));
    }

    @Test
    void userResponseWithoutAvatarHasNullAvatarUrl() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get(USERS + "/{id}", user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(nullValue()));
    }

    @Test
    void taskAndHistoryPreviewsCarryAvatarUrls() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);

        uploadAndAwaitAvatar(admin, asAdmin(admin), "admin.png", opaquePng());
        uploadOwnAvatar(assignee, opaquePng());
        String adminKey = avatarStorageKey(admin);
        String assigneeKey = avatarStorageKey(assignee);

        UUID taskId = createTask(admin, assignee);

        String task = mockMvc.perform(get("/api/v1/tasks/{id}", taskId).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.avatarUrl", containsString(assigneeKey)))
                .andExpect(jsonPath("$.createdBy.avatarUrl", containsString(adminKey)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        mockMvc.perform(get("/api/v1/tasks/{taskId}/events", taskId).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.avatarUrl", containsString(adminKey)));

        String assigneeAvatarUrl = json(task).get("assignedTo").get("avatarUrl").asString();

        assertThat(download(assigneeAvatarUrl).statusCode()).isEqualTo(200);
    }

    @Test
    void taskAndHistoryPreviewsWithoutAvatarHaveNullAvatarUrl() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);

        UUID taskId = createTask(admin, assignee);

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(assignee.getId().toString()))
                .andExpect(jsonPath("$.assignedTo.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.createdBy.avatarUrl").value(nullValue()));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/events", taskId).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.content[0].actor.avatarUrl").value(nullValue()));
    }

    private UUID createTask(User admin, User assignee) throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/tasks")
                                .with(asAdmin(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reference": "A-%s", "title": "Task", "description": "Avatar previews",
                                         "priority": "LOW", "dueAt": "2030-01-01T10:00:00Z", "assignedTo": "%s"}
                                        """.formatted(UUID.randomUUID().toString().substring(0, 8), assignee.getId()))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(json(body).get("id").asString());
    }
}
