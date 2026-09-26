package io.julienmetral.tasks.shared.exceptions;

import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.ConnectException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void taskNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleTaskNotFound(new TaskNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Task not found");
        assertThat(problem.getDetail()).isEqualTo("Task not found with id: " + ID);
    }

    @Test
    void taskNotFoundByReferenceMapsTo404() {
        ProblemDetail problem = handler.handleTaskNotFound(new TaskNotFoundException("TASK-1"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("Task not found with reference: TASK-1");
    }

    @Test
    void taskAttachmentNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleTaskAttachmentNotFound(new TaskAttachmentNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Attachment not found");
        assertThat(problem.getDetail()).isEqualTo("Attachment not found with id: " + ID);
    }

    @Test
    void taskCommentNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleTaskCommentNotFound(new TaskCommentNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Comment not found");
        assertThat(problem.getDetail()).isEqualTo("Comment not found with id: " + ID);
    }

    @Test
    void mentionOfUnknownUserMapsTo422() {
        ProblemDetail problem = handler.handleInvalidMention(InvalidMentionException.unknownUser(ID));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("User cannot be mentioned");
        assertThat(problem.getDetail()).isEqualTo("User " + ID + " cannot be mentioned: no such user");
    }

    @Test
    void mentionOfInactiveUserMapsTo422WithTheStatus() {
        ProblemDetail problem = handler.handleInvalidMention(
                InvalidMentionException.inactiveUser(ID, UserStatus.UNVERIFIED));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("User cannot be mentioned");
        assertThat(problem.getDetail()).isEqualTo("User " + ID + " cannot be mentioned: account is UNVERIFIED");
    }

    @Test
    void tooManyCommentAttachmentsMapsTo400WithTheLimit() {
        ProblemDetail problem = handler.handleTooManyCommentAttachments(new TooManyCommentAttachmentsException(5));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Too many files");
        assertThat(problem.getDetail()).isEqualTo("A comment can have at most 5 files");
    }

    @Test
    void taskReferenceAlreadyExistsMapsTo409() {
        ProblemDetail problem = handler.handleTaskReferenceAlreadyExists(
                new TaskReferenceAlreadyExistsException("TASK-1"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Task reference already exists");
        assertThat(problem.getDetail()).isEqualTo("Task reference already exists: TASK-1");
    }

    @Test
    void userNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleUserNotFound(new UserNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("User not found");
        assertThat(problem.getDetail()).isEqualTo("User not found with id: " + ID);
    }

    @Test
    void userNotFoundByEmailMapsTo404() {
        ProblemDetail problem = handler.handleUserNotFound(new UserNotFoundException("a@b.c"));

        assertThat(problem.getDetail()).isEqualTo("User not found with email: a@b.c");
    }

    @Test
    void userEmailAlreadyExistsMapsTo409() {
        ProblemDetail problem = handler.handleUserEmailAlreadyExists(
                new UserEmailAlreadyExistsException("a@b.c"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("User email already exists");
        assertThat(problem.getDetail()).isEqualTo("User already exists with email: a@b.c");
    }

    @Test
    void dataIntegrityViolationMapsTo409WithoutLeakingSqlDetails() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_emailuq\"")
        );

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Data conflict");
        assertThat(problem.getDetail()).doesNotContain("users_emailuq");
    }

    @Test
    void invalidCredentialsMapsTo401WithMessageBody() {
        ResponseEntity<?> response = handler.handleInvalidCredentials(new InvalidCredentialsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "status", 401,
                "message", "Invalid email or password"
        ));
    }

    @Test
    void invalidRefreshTokenMapsTo401() {
        ProblemDetail problem = handler.handleInvalidRefreshToken(new InvalidRefreshTokenException());

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo("Invalid refresh token");
        assertThat(problem.getDetail()).isEqualTo("The refresh token is invalid, expired or revoked");
    }

    @Test
    void invalidEmailVerificationTokenMapsTo400() {
        ProblemDetail problem = handler.handleInvalidEmailVerificationToken(
                new InvalidEmailVerificationTokenException());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Invalid email verification token");
        assertThat(problem.getDetail()).isEqualTo("The email verification token is invalid, expired or already used");
    }

    @Test
    void emailAlreadyVerifiedMapsTo409() {
        ProblemDetail problem = handler.handleEmailAlreadyVerified(new EmailAlreadyVerifiedException());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Email already verified");
        assertThat(problem.getDetail()).isEqualTo("The email address is already verified");
    }

    @Test
    void assigneeNotActiveMapsTo422() {
        ProblemDetail problem = handler.handleAssigneeNotActive(
                new AssigneeNotActiveException(ID, UserStatus.DISABLED));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("User cannot be assigned");
        assertThat(problem.getDetail())
                .isEqualTo("User " + ID + " cannot be assigned a task: account is DISABLED");
    }

    @Test
    void invalidPasswordResetTokenMapsTo400() {
        ProblemDetail problem = handler.handleInvalidPasswordResetToken(
                new InvalidPasswordResetTokenException());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Invalid password reset token");
        assertThat(problem.getDetail()).isEqualTo("The password reset token is invalid, expired or already used");
    }

    @Test
    void emptyMediaMapsTo400() {
        ProblemDetail problem = handler.handleEmptyMedia(new EmptyMediaException());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Empty file");
        assertThat(problem.getDetail()).isEqualTo("The file is empty");
    }

    @Test
    void mediaTooLargeMapsTo413WithTheLimit() {
        ProblemDetail problem = handler.handleMediaTooLarge(new MediaTooLargeException(DataSize.ofMegabytes(5)));

        assertThat(problem.getStatus()).isEqualTo(413);
        assertThat(problem.getTitle()).isEqualTo("File too large");
        assertThat(problem.getDetail()).isEqualTo("The file exceeds the maximum size of 5 MB");
    }

    @Test
    void maxUploadSizeExceededMapsTo413WithoutParserDetails() {
        ProblemDetail problem = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(26_214_400L, new IllegalStateException("tomcat internals")));

        assertThat(problem.getStatus()).isEqualTo(413);
        assertThat(problem.getTitle()).isEqualTo("File too large");
        assertThat(problem.getDetail()).isEqualTo("The request exceeds the maximum upload size");
    }

    @Test
    void unsupportedMediaTypeMapsTo415WithTheDetectedType() {
        ProblemDetail problem = handler.handleUnsupportedMediaType(
                new UnsupportedMediaTypeException("application/x-msdownload", MediaUsage.TASK_ATTACHMENT));

        assertThat(problem.getStatus()).isEqualTo(415);
        assertThat(problem.getTitle()).isEqualTo("Unsupported file type");
        assertThat(problem.getDetail())
                .isEqualTo("Files of type application/x-msdownload are not accepted for TASK_ATTACHMENT");
    }

    @Test
    void storageUnavailableMapsTo503WithoutLeakingTheCause() {
        ProblemDetail problem = handler.handleStorageUnavailable(
                new StorageUnavailableException(new RuntimeException("Connection refused: rustfs:9000")));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("Storage unavailable");
        assertThat(problem.getDetail())
                .isEqualTo("File storage is temporarily unavailable")
                .doesNotContain("rustfs");
    }

    @Test
    void invalidImageMapsTo422WithTheReason() {
        ProblemDetail problem = handler.handleInvalidImage(
                new InvalidImageException("dimensions 50000x50000 are too large"));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("Invalid image");
        assertThat(problem.getDetail())
                .isEqualTo("The image cannot be used: dimensions 50000x50000 are too large");
    }

    @Test
    void infectedMediaMapsTo422NamingTheThreat() {
        ProblemDetail problem = handler.handleInfectedMedia(new InfectedMediaException("Win.Test.EICAR_HDB-1"));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("File rejected by the antivirus");
        assertThat(problem.getDetail()).isEqualTo("The file was rejected by the antivirus: Win.Test.EICAR_HDB-1");
    }

    @Test
    void antivirusUnavailableMapsTo503WithoutLeakingTheCause() {
        ProblemDetail problem = handler.handleAntivirusUnavailable(
                new AntivirusUnavailableException(new ConnectException("Connection refused: clamav:3310")));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("Antivirus unavailable");
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
}
