package io.julienmetral.tasks.shared.exceptions;

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
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problem.setTitle("Task not found");

        return problem;
    }

    @ExceptionHandler(AssigneeNotActiveException.class)
    public ProblemDetail handleAssigneeNotActive(AssigneeNotActiveException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_CONTENT,
            ex.getMessage()
        );

        problem.setTitle("User cannot be assigned");

        return problem;
    }

    @ExceptionHandler(TaskAttachmentNotFoundException.class)
    public ProblemDetail handleTaskAttachmentNotFound(TaskAttachmentNotFoundException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problem.setTitle("Attachment not found");

        return problem;
    }

    @ExceptionHandler(TaskCommentNotFoundException.class)
    public ProblemDetail handleTaskCommentNotFound(TaskCommentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Comment not found");
        return problem;
    }

    @ExceptionHandler(InvalidMentionException.class)
    public ProblemDetail handleInvalidMention(InvalidMentionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("User cannot be mentioned");
        return problem;
    }

    @ExceptionHandler(TooManyCommentAttachmentsException.class)
    public ProblemDetail handleTooManyCommentAttachments(TooManyCommentAttachmentsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Too many files");
        return problem;
    }

    @ExceptionHandler(TaskReferenceAlreadyExistsException.class)
    public ProblemDetail handleTaskReferenceAlreadyExists(
        TaskReferenceAlreadyExistsException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problem.setTitle("Task reference already exists");

        return problem;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problem.setTitle("User not found");

        return problem;
    }

    @ExceptionHandler(UserEmailAlreadyExistsException.class)
    public ProblemDetail handleUserEmailAlreadyExists(
        UserEmailAlreadyExistsException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problem.setTitle("User email already exists");

        return problem;
    }

    // Safety net for races between an existence check and the insert (e.g. two concurrent sign-ups with one email).
    // The detail stays generic so no SQL or constraint name leaks to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(
        DataIntegrityViolationException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "The request conflicts with existing data"
        );

        problem.setTitle("Data conflict");

        return problem;
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(
        InvalidRefreshTokenException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            ex.getMessage()
        );

        problem.setTitle("Invalid refresh token");

        return problem;
    }

    @ExceptionHandler(InvalidEmailVerificationTokenException.class)
    public ProblemDetail handleInvalidEmailVerificationToken(
        InvalidEmailVerificationTokenException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );

        problem.setTitle("Invalid email verification token");

        return problem;
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ProblemDetail handleInvalidPasswordResetToken(
        InvalidPasswordResetTokenException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );

        problem.setTitle("Invalid password reset token");

        return problem;
    }

    @ExceptionHandler(EmailAlreadyVerifiedException.class)
    public ProblemDetail handleEmailAlreadyVerified(
        EmailAlreadyVerifiedException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problem.setTitle("Email already verified");

        return problem;
    }

    @ExceptionHandler(EmptyMediaException.class)
    public ProblemDetail handleEmptyMedia(EmptyMediaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Empty file");
        return problem;
    }

    @ExceptionHandler(MediaTooLargeException.class)
    public ProblemDetail handleMediaTooLarge(MediaTooLargeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
        problem.setTitle("File too large");
        return problem;
    }

    // Raised by the multipart parser before the request reaches a controller (spring.servlet.multipart limits)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONTENT_TOO_LARGE,
            "The request exceeds the maximum upload size"
        );
        problem.setTitle("File too large");
        return problem;
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ProblemDetail handleUnsupportedMediaType(UnsupportedMediaTypeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Unsupported file type");
        return problem;
    }

    @ExceptionHandler(InvalidImageException.class)
    public ProblemDetail handleInvalidImage(InvalidImageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Invalid image");
        return problem;
    }

    @ExceptionHandler(InfectedMediaException.class)
    public ProblemDetail handleInfectedMedia(InfectedMediaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("File rejected by the antivirus");
        return problem;
    }

    // Fail closed: an upload is never stored unscanned while the antivirus is enabled
    @ExceptionHandler(AntivirusUnavailableException.class)
    public ProblemDetail handleAntivirusUnavailable(AntivirusUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The antivirus is temporarily unavailable, try again later"
        );
        problem.setTitle("Antivirus unavailable");
        return problem;
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ProblemDetail handleStorageUnavailable(StorageUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Storage unavailable");
        return problem;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Too many requests");

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
            .body(problem);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<?> handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(
                        Map.of(
                                "status", 401,
                                "message", exception.getMessage()
                        )
                );
    }
}