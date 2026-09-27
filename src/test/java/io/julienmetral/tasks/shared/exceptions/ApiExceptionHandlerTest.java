package io.julienmetral.tasks.shared.exceptions;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.unit.DataSize;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String TYPES = "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md#";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void taskNotFoundMapsToAnUntyped404() {
        ProblemDetail problem = handler.handleNotFound(new TaskNotFoundException(ID));

        assertUntyped(problem, 404, "Not Found");
        assertThat(problem.getDetail()).isEqualTo("Task not found with id: " + ID);
    }

    @Test
    void taskNotFoundByReferenceMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new TaskNotFoundException("TASK-1"));

        assertUntyped(problem, 404, "Not Found");
        assertThat(problem.getDetail()).isEqualTo("Task not found with reference: TASK-1");
    }

    @Test
    void taskAttachmentNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new TaskAttachmentNotFoundException(ID));

        assertUntyped(problem, 404, "Not Found");
        assertThat(problem.getDetail()).isEqualTo("Attachment not found with id: " + ID);
    }

    @Test
    void taskCommentNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new TaskCommentNotFoundException(ID));

        assertUntyped(problem, 404, "Not Found");
        assertThat(problem.getDetail()).isEqualTo("Comment not found with id: " + ID);
    }

    @Test
    void userNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new UserNotFoundException(ID));

        assertUntyped(problem, 404, "Not Found");
        assertThat(problem.getDetail()).isEqualTo("User not found with id: " + ID);
    }

    @Test
    void userNotFoundByEmailMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new UserNotFoundException("a@b.c"));

        assertThat(problem.getDetail()).isEqualTo("User not found with email: a@b.c");
    }

    @Test
    void mentionOfUnknownUserIsAnInvalidMention() {
        ProblemDetail problem = handler.handleInvalidMention(InvalidMentionException.unknownUser(ID));

        assertTyped(problem, 422, "invalid-mention", "Invalid mention");
        assertThat(problem.getDetail()).isEqualTo("User " + ID + " cannot be mentioned: no such user");
    }

    @Test
    void mentionOfInactiveUserIsAnInvalidMentionNamingTheStatus() {
        ProblemDetail problem = handler.handleInvalidMention(
                InvalidMentionException.inactiveUser(ID, UserStatus.UNVERIFIED));

        assertTyped(problem, 422, "invalid-mention", "Invalid mention");
        assertThat(problem.getDetail()).isEqualTo("User " + ID + " cannot be mentioned: account is UNVERIFIED");
    }

    @Test
    void tooManyCommentAttachmentsIsAValidationErrorOnTheFilesParameter() {
        ProblemDetail problem = handler.handleTooManyCommentAttachments(new TooManyCommentAttachmentsException(5));

        assertTyped(problem, 400, "validation-error", "Invalid request");
        assertThat(problem.getDetail()).isEqualTo("One or more values of the request are invalid: see errors.");
        assertThat(problem.getProperties()).containsEntry(
                "errors",
                List.of(new InvalidValue("A comment can have at most 5 files", null, "files"))
        );
    }

    @Test
    void taskReferenceAlreadyExistsIsReferenceTaken() {
        ProblemDetail problem = handler.handleReferenceTaken(new TaskReferenceAlreadyExistsException("TASK-1"));

        assertTyped(problem, 409, "reference-taken", "Task reference already in use");
        assertThat(problem.getDetail()).isEqualTo("Task reference already exists: TASK-1");
    }

    @Test
    void userEmailAlreadyExistsIsEmailTaken() {
        ProblemDetail problem = handler.handleEmailTaken(new UserEmailAlreadyExistsException("a@b.c"));

        assertTyped(problem, 409, "email-taken", "Email already in use");
        assertThat(problem.getDetail()).isEqualTo("User already exists with email: a@b.c");
    }

    @Test
    void optimisticLockingFailureIsAVersionConflictWithoutTheEntityDetails() {
        ProblemDetail problem = handler.handleVersionConflict(
                new ObjectOptimisticLockingFailureException(Task.class, ID));

        assertTyped(problem, 409, "version-conflict", "Changed by another request");
        assertThat(problem.getDetail())
                .isEqualTo("Another request changed this resource at the same time: reload it and try again.")
                .doesNotContain(ID.toString(), Task.class.getName());
    }

    @Test
    void dataIntegrityViolationMapsToAnUntyped409WithoutLeakingSqlDetails() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_emailuq\"")
        );

        assertUntyped(problem, 409, "Conflict");
        assertThat(problem.getDetail())
                .isEqualTo("The request conflicts with existing data")
                .doesNotContain("users_emailuq");
    }

    @Test
    void invalidCredentialsMapsToAnUntyped401Problem() {
        ProblemDetail problem = handler.handleUnauthorized(new InvalidCredentialsException());

        assertUntyped(problem, 401, "Unauthorized");
        assertThat(problem.getDetail()).isEqualTo("Invalid email or password");
    }

    @Test
    void rateLimitExceededMapsTo429WithRetryAfterInSeconds() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(Duration.ofMinutes(42).plusSeconds(17)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().get(HttpHeaders.RETRY_AFTER)).containsExactly("2537");

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertUntyped(problem, 429, "Too Many Requests");
        assertThat(problem.getDetail()).isEqualTo("Too many requests, try again in 2537 seconds");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 500, 999})
    void retryAfterUnderOneSecondIsOneSecond(long millis) {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(Duration.ofMillis(millis)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void retryAfterOfWholeSecondsIsKeptAsIs() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(Duration.ofSeconds(60)));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    }

    @Test
    void retryAfterWithAFractionOfASecondIsRoundedUp() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(Duration.ofMillis(1_500)));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Too many requests, try again in 2 seconds");
    }

    @Test
    void invalidRefreshTokenMapsToAnUntyped401() {
        ProblemDetail problem = handler.handleUnauthorized(new InvalidRefreshTokenException());

        assertUntyped(problem, 401, "Unauthorized");
        assertThat(problem.getDetail()).isEqualTo("The refresh token is invalid, expired or revoked");
    }

    @Test
    void invalidEmailVerificationTokenIsAnInvalidToken() {
        ProblemDetail problem = handler.handleInvalidToken(new InvalidEmailVerificationTokenException());

        assertTyped(problem, 400, "invalid-token", "Invalid or expired token");
        assertThat(problem.getDetail()).isEqualTo("The email verification token is invalid, expired or already used");
    }

    @Test
    void invalidPasswordResetTokenIsAnInvalidToken() {
        ProblemDetail problem = handler.handleInvalidToken(new InvalidPasswordResetTokenException());

        assertTyped(problem, 400, "invalid-token", "Invalid or expired token");
        assertThat(problem.getDetail()).isEqualTo("The password reset token is invalid, expired or already used");
    }

    @Test
    void emailAlreadyVerifiedIsTyped() {
        ProblemDetail problem = handler.handleEmailAlreadyVerified(new EmailAlreadyVerifiedException());

        assertTyped(problem, 409, "email-already-verified", "Email already verified");
        assertThat(problem.getDetail()).isEqualTo("The email address is already verified");
    }

    @Test
    void assigneeNotActiveIsTypedAndNamesTheStatus() {
        ProblemDetail problem = handler.handleAssigneeNotActive(
                new AssigneeNotActiveException(ID, UserStatus.DISABLED));

        assertTyped(problem, 422, "assignee-not-active", "Assignee not active");
        assertThat(problem.getDetail())
                .isEqualTo("User " + ID + " cannot be assigned a task: account is DISABLED");
    }

    @Test
    void emptyMediaMapsToAnUntyped400() {
        ProblemDetail problem = handler.handleEmptyMedia(new EmptyMediaException());

        assertUntyped(problem, 400, "Bad Request");
        assertThat(problem.getDetail()).isEqualTo("The file is empty");
    }

    @Test
    void mediaTooLargeMapsToAnUntyped413WithTheLimit() {
        ProblemDetail problem = handler.handleMediaTooLarge(new MediaTooLargeException(DataSize.ofMegabytes(5)));

        assertUntyped(problem, 413, "Content Too Large");
        assertThat(problem.getDetail()).isEqualTo("The file exceeds the maximum size of 5 MB");
    }

    @Test
    void unsupportedMediaTypeMapsToAnUntyped415WithTheDetectedType() {
        ProblemDetail problem = handler.handleUnsupportedMediaType(
                new UnsupportedMediaTypeException("application/x-msdownload", MediaUsage.TASK_ATTACHMENT));

        assertUntyped(problem, 415, "Unsupported Media Type");
        assertThat(problem.getDetail())
                .isEqualTo("Files of type application/x-msdownload are not accepted for TASK_ATTACHMENT");
    }

    @Test
    void storageUnavailableMapsToAnUntyped503WithoutLeakingTheCause() {
        ProblemDetail problem = handler.handleStorageUnavailable(
                new StorageUnavailableException(new RuntimeException("Connection refused: rustfs:9000")));

        assertUntyped(problem, 503, "Service Unavailable");
        assertThat(problem.getDetail())
                .isEqualTo("File storage is temporarily unavailable")
                .doesNotContain("rustfs");
    }

    @Test
    void invalidImageIsTypedWithTheReason() {
        ProblemDetail problem = handler.handleInvalidImage(
                new InvalidImageException("dimensions 50000x50000 are too large"));

        assertTyped(problem, 422, "invalid-image", "Invalid image");
        assertThat(problem.getDetail())
                .isEqualTo("The image cannot be used: dimensions 50000x50000 are too large");
    }

    @Test
    void infectedMediaIsTypedAndNamesTheThreat() {
        ProblemDetail problem = handler.handleInfectedMedia(new InfectedMediaException("Win.Test.EICAR_HDB-1"));

        assertTyped(problem, 422, "infected-file", "File rejected by the antivirus");
        assertThat(problem.getDetail()).isEqualTo("The file was rejected by the antivirus: Win.Test.EICAR_HDB-1");
    }

    @Test
    void antivirusUnavailableMapsToAnUntyped503WithoutLeakingTheCause() {
        ProblemDetail problem = handler.handleAntivirusUnavailable(
                new AntivirusUnavailableException(new ConnectException("Connection refused: clamav:3310")));

        assertUntyped(problem, 503, "Service Unavailable");
        assertThat(problem.getDetail())
                .isEqualTo("The antivirus is temporarily unavailable, try again later")
                .doesNotContain("clamav");
    }

    @Test
    void antivirusErrorReplyIsNotLeaked() {
        ProblemDetail problem = handler.handleAntivirusUnavailable(
                new AntivirusUnavailableException("Unexpected clamd reply: INSTREAM size limit exceeded. ERROR"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).doesNotContain("clamd", "INSTREAM");
    }

    // getTitle() falls back to the status phrase when no title is set, which is also what the JSON carries
    private static void assertUntyped(ProblemDetail problem, int status, String statusPhrase) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getType()).isNull();
        assertThat(problem.getTitle()).isEqualTo(statusPhrase);
    }

    private static void assertTyped(ProblemDetail problem, int status, String slug, String title) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getType()).isEqualTo(URI.create(TYPES + slug));
        assertThat(problem.getTitle()).isEqualTo(title);
    }
}
