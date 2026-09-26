package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.services.AvatarService;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.user;
import static io.julienmetral.tasks.support.WebCallers.withoutUid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class UserControllerWebMvcTests {

    private static final String USERS = "/api/v1/users";

    private static final String USER = USERS + "/{id}";

    private static final String AVATAR = USER + "/avatar";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private AvatarService avatarService;

    @Autowired
    private RateLimiter rateLimiter;

    @Nested
    class SignUp {

        @Test
        void signUpWithoutTokenIsAccepted() throws Exception {
            when(userService.create("alice@example.com", "password123", "Alice"))
                    .thenReturn(account(UUID.randomUUID()));

            signUp(signUpBody("alice@example.com", "password123", "Alice"))
                    .andExpect(status().isCreated());
        }

        @Test
        void signUpRejectsInvalidEmail() throws Exception {
            expectRejected(signUpBody("not-an-email", "password123", "Dave"));
        }

        @Test
        void signUpRejectsBlankEmail() throws Exception {
            expectRejected(signUpBody("  ", "password123", "Dave"));
        }

        @Test
        void signUpRejectsShortPassword() throws Exception {
            expectRejected(signUpBody(uniqueEmail(), "short", "Dave"));
        }

        @Test
        void signUpRejectsTooLongPassword() throws Exception {
            expectRejected(signUpBody(uniqueEmail(), "p".repeat(129), "Dave"));
        }

        @Test
        void signUpRejectsBlankDisplayName() throws Exception {
            expectRejected(signUpBody(uniqueEmail(), "password123", "   "));
        }

        @Test
        void signUpRejectsTooLongDisplayName() throws Exception {
            expectRejected(signUpBody(uniqueEmail(), "password123", "d".repeat(256)));
        }

        @Test
        void signUpRejectsMissingFields() throws Exception {
            expectRejected("{}");
        }

        @Test
        void signUpRejectsMalformedJson() throws Exception {
            expectRejected("{\"email\": ");
        }

        @Test
        void signUpWithTakenEmailReturnsConflictProblem() throws Exception {
            when(userService.create(anyString(), anyString(), anyString()))
                    .thenThrow(new UserEmailAlreadyExistsException("taken@example.com"));

            signUp(signUpBody("taken@example.com", "password123", "Second"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("User email already exists"));
        }

        @Test
        void signUpLosingARaceOnTheEmailReturnsAGenericConflict() throws Exception {
            when(userService.create(anyString(), anyString(), anyString()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates users_emailUQ"));

            signUp(signUpBody(uniqueEmail(), "password123", "Racer"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Data conflict"))
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
        }

        @Test
        void signUpOverTheRateLimitReturnsTooManyRequestsBeforeCreatingTheUser() throws Exception {
            doThrow(new RateLimitExceededException(Duration.ofSeconds(90))).when(rateLimiter).signUp(anyString());

            signUp(signUpBody(uniqueEmail(), "password123", "Eve"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "90"))
                    .andExpect(jsonPath("$.title").value("Too many requests"));

            verifyNoInteractions(userService);
        }

        private void expectRejected(String body) throws Exception {
            signUp(body).andExpect(status().isBadRequest());

            verifyNoInteractions(userService);
        }

        private ResultActions signUp(String body) throws Exception {
            return mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }

    @Nested
    class Reading {

        @Test
        void getUserWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(get(USER, UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void userCannotReadAnotherUser() throws Exception {
            mockMvc.perform(get(USER, UUID.randomUUID()).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void userWithoutUidClaimCannotReadAnyUser() throws Exception {
            mockMvc.perform(get(USER, UUID.randomUUID()).with(withoutUid()))
                    .andExpect(status().isForbidden());
        }

        @Test
        void userReadingUnknownIdGetsForbiddenNotNotFound() throws Exception {
            UUID unknown = UUID.randomUUID();
            when(userService.findById(unknown)).thenThrow(new UserNotFoundException(unknown));

            mockMvc.perform(get(USER, unknown).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void userCanReadSelfWithoutAnyRoleCheck() throws Exception {
            UUID id = UUID.randomUUID();
            when(userService.findById(id)).thenReturn(account(id));

            mockMvc.perform(get(USER, id).with(user(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()));
        }

        @Test
        void userTheServiceCannotFindReturnsNotFoundProblem() throws Exception {
            UUID unknown = UUID.randomUUID();
            when(userService.findById(unknown)).thenThrow(new UserNotFoundException(unknown));

            mockMvc.perform(get(USER, unknown).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("User not found"))
                    .andExpect(jsonPath("$.detail").value("User not found with id: " + unknown));
        }

        @Test
        void getUserWithInvalidUuidReturnsBadRequest() throws Exception {
            mockMvc.perform(get(USER, "not-a-uuid").with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Updating {

        @Test
        void updateRejectsTooLongDisplayName() throws Exception {
            UUID id = UUID.randomUUID();

            update(id, user(id), "{\"displayName\": \"%s\"}".formatted("x".repeat(256)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userService);
        }

        @Test
        void updateRejectsMalformedJson() throws Exception {
            UUID id = UUID.randomUUID();

            update(id, user(id), "{\"displayName\": ")
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userService);
        }

        @Test
        void updateWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(patch(USER, UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\": \"Anon\"}"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(userService);
        }

        @Test
        void userCannotUpdateAnotherUser() throws Exception {
            update(UUID.randomUUID(), user(UUID.randomUUID()), "{\"displayName\": \"Hacked\"}")
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void adminPassesTheCheckToUpdateAnotherUser() throws Exception {
            UUID other = UUID.randomUUID();
            when(userService.updateProfile(other, "Set by admin")).thenReturn(account(other));

            update(other, admin(UUID.randomUUID()), "{\"displayName\": \"Set by admin\"}")
                    .andExpect(status().isOk());
        }

        private ResultActions update(UUID id, RequestPostProcessor caller, String body) throws Exception {
            return mockMvc.perform(patch(USER, id).with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }

    @Nested
    class Administration {

        @Test
        void enableAndDisableWithoutTokenReturnUnauthorized() throws Exception {
            mockMvc.perform(post(USER + "/enable", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(USER + "/disable", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void userCannotDisableOrEnableUsersIncludingSelf() throws Exception {
            UUID self = UUID.randomUUID();

            mockMvc.perform(post(USER + "/disable", UUID.randomUUID()).with(user(self)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(USER + "/disable", self).with(user(self)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(USER + "/enable", self).with(user(self)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void deleteWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(delete(USER, UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void userCannotDeleteUsersIncludingSelf() throws Exception {
            UUID self = UUID.randomUUID();

            mockMvc.perform(delete(USER, UUID.randomUUID()).with(user(self)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(USER, self).with(user(self)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void adminPassesTheAdminOnlyChecks() throws Exception {
            UUID other = UUID.randomUUID();

            mockMvc.perform(post(USER + "/disable", other).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post(USER + "/enable", other).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete(USER, other).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());

            verify(userService).disable(other);
            verify(userService).enable(other);
            verify(userService).delete(other);
        }

        @Test
        void operationOnAUserTheServiceCannotFindReturnsNotFoundProblem() throws Exception {
            UUID unknown = UUID.randomUUID();
            doThrow(new UserNotFoundException(unknown)).when(userService).disable(unknown);

            mockMvc.perform(post(USER + "/disable", unknown).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("User not found"));
        }
    }

    @Nested
    class Avatar {

        @Test
        void uploadWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(avatarUpload(UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(avatarService);
        }

        @Test
        void userCannotUploadAvatarOfAnotherUser() throws Exception {
            mockMvc.perform(avatarUpload(UUID.randomUUID()).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(avatarService);
        }

        @Test
        void uploadWithoutFilePartReturnsBadRequest() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(multipart(HttpMethod.PUT, AVATAR, id).with(user(id)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(avatarService);
        }

        @Test
        void acceptedUploadAnswersAccepted() throws Exception {
            UUID id = UUID.randomUUID();
            when(avatarService.update(eq(id), any())).thenReturn(account(id));

            mockMvc.perform(avatarUpload(id).with(user(id)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(id.toString()));
        }

        @Test
        void unsupportedTypeReturnsUnsupportedMediaType() throws Exception {
            expectUploadRejected(
                    new UnsupportedMediaTypeException("application/pdf", MediaUsage.AVATAR_UPLOAD)
            )
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.title").value("Unsupported file type"));
        }

        @Test
        void tooLargeFileReturnsContentTooLarge() throws Exception {
            expectUploadRejected(new MediaTooLargeException(DataSize.ofMegabytes(5)))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.title").value("File too large"));
        }

        @Test
        void requestAboveTheMultipartLimitReturnsContentTooLargeWithoutParserDetails() throws Exception {
            expectUploadRejected(new MaxUploadSizeExceededException(26L * 1024 * 1024))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.title").value("File too large"))
                    .andExpect(jsonPath("$.detail").value("The request exceeds the maximum upload size"));
        }

        @Test
        void emptyFileReturnsBadRequest() throws Exception {
            expectUploadRejected(new EmptyMediaException())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Empty file"));
        }

        @Test
        void invalidImageReturnsUnprocessable() throws Exception {
            expectUploadRejected(new InvalidImageException("the image is larger than 10000 pixels"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("Invalid image"));
        }

        @Test
        void infectedFileReturnsUnprocessableNamingTheThreat() throws Exception {
            expectUploadRejected(new InfectedMediaException("Eicar-Test-Signature"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("File rejected by the antivirus"))
                    .andExpect(jsonPath("$.detail")
                            .value("The file was rejected by the antivirus: Eicar-Test-Signature"));
        }

        @Test
        void unreachableAntivirusReturnsServiceUnavailableWithoutTheCause() throws Exception {
            expectUploadRejected(new AntivirusUnavailableException(new IOException("Connection refused: clamav:3310")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Antivirus unavailable"))
                    .andExpect(jsonPath("$.detail").value("The antivirus is temporarily unavailable, try again later"));
        }

        @Test
        void unreachableStorageReturnsServiceUnavailable() throws Exception {
            expectUploadRejected(new StorageUnavailableException(new IOException("Connection refused: rustfs:9000")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Storage unavailable"))
                    .andExpect(jsonPath("$.detail").value("File storage is temporarily unavailable"));
        }

        @Test
        void removeWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(delete(AVATAR, UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(avatarService);
        }

        @Test
        void userCannotRemoveAvatarOfAnotherUser() throws Exception {
            mockMvc.perform(delete(AVATAR, UUID.randomUUID()).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(avatarService);
        }

        private ResultActions expectUploadRejected(RuntimeException rejection) throws Exception {
            UUID id = UUID.randomUUID();
            when(avatarService.update(eq(id), any())).thenThrow(rejection);

            return mockMvc.perform(avatarUpload(id).with(user(id)));
        }

        private MockMultipartHttpServletRequestBuilder avatarUpload(UUID id) {
            return multipart(HttpMethod.PUT, AVATAR, id)
                    .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3}));
        }
    }

    private static User account(UUID id) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@example.com");
        user.setDisplayName("Test user");
        user.setRoles(Set.of(UserRole.USER));
        return user;
    }

    private static String signUpBody(String email, String password, String displayName) {
        return """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, password, displayName);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
