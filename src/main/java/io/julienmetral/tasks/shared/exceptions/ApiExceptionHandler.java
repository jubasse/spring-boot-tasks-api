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
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.notification.exceptions.WebhookEndpointNotFoundException;
import io.julienmetral.tasks.notification.exceptions.WebhookLimitReachedException;
import io.julienmetral.tasks.notification.exceptions.WebhookUrlNotAllowedException;
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.List;
import java.util.Locale;

/**
 * Every error answered as an RFC 9457 problem. The Spring MVC exceptions come from
 * {@link ResponseEntityExceptionHandler}, with their details set in {@code messages.properties}; invalid input
 * becomes a {@link ProblemType#VALIDATION_ERROR} listing each invalid value. See {@link ProblemType} for when an error
 * gets a type of its own.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_REQUEST = "One or more values of the request are invalid: see errors.";

    @ExceptionHandler({
            TaskNotFoundException.class,
            TaskAttachmentNotFoundException.class,
            TaskCommentNotFoundException.class,
            UserNotFoundException.class,
            WebhookEndpointNotFoundException.class
    })
    public ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    public ProblemDetail handleUnauthorized(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler({InvalidEmailVerificationTokenException.class, InvalidPasswordResetTokenException.class})
    public ProblemDetail handleInvalidToken(RuntimeException ex) {
        return ProblemType.INVALID_TOKEN.problem(ex.getMessage());
    }

    @ExceptionHandler(UserEmailAlreadyExistsException.class)
    public ProblemDetail handleEmailTaken(UserEmailAlreadyExistsException ex) {
        return ProblemType.EMAIL_TAKEN.problem(ex.getMessage());
    }

    @ExceptionHandler(TaskReferenceAlreadyExistsException.class)
    public ProblemDetail handleReferenceTaken(TaskReferenceAlreadyExistsException ex) {
        return ProblemType.REFERENCE_TAKEN.problem(ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyVerifiedException.class)
    public ProblemDetail handleEmailAlreadyVerified(EmailAlreadyVerifiedException ex) {
        return ProblemType.EMAIL_ALREADY_VERIFIED.problem(ex.getMessage());
    }

    // The client sends no version, so this catches two requests updating the same row at the same time, not a client
    // writing over a change it never read
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleVersionConflict(OptimisticLockingFailureException ex) {
        return ProblemType.VERSION_CONFLICT.problem(
                "Another request changed this resource at the same time: reload it and try again."
        );
    }

    // Safety net for races between an existence check and the insert (e.g. two concurrent sign-ups with one email).
    // The detail stays generic so no SQL or constraint name leaks to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler(AssigneeNotActiveException.class)
    public ProblemDetail handleAssigneeNotActive(AssigneeNotActiveException ex) {
        return ProblemType.ASSIGNEE_NOT_ACTIVE.problem(ex.getMessage());
    }

    @ExceptionHandler(WebhookUrlNotAllowedException.class)
    public ProblemDetail handleWebhookUrlNotAllowed(WebhookUrlNotAllowedException ex) {
        return ProblemType.WEBHOOK_URL_NOT_ALLOWED.problem(ex.getMessage());
    }

    @ExceptionHandler(WebhookLimitReachedException.class)
    public ProblemDetail handleWebhookLimitReached(WebhookLimitReachedException ex) {
        return ProblemType.WEBHOOK_LIMIT_REACHED.problem(ex.getMessage());
    }

    @ExceptionHandler(InvalidMentionException.class)
    public ProblemDetail handleInvalidMention(InvalidMentionException ex) {
        return ProblemType.INVALID_MENTION.problem(ex.getMessage());
    }

    @ExceptionHandler(TooManyCommentAttachmentsException.class)
    public ProblemDetail handleTooManyCommentAttachments(TooManyCommentAttachmentsException ex) {
        return invalidRequest(List.of(InvalidValue.inParameter(ex.getMessage(), "files")));
    }

    // Spring Data raises it while building the query of a page whose sort names a property the entity does not have
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        return invalidRequest(List.of(InvalidValue.inParameter(
                "cannot sort by " + ex.getPropertyName(),
                "sort"
        )));
    }

    @ExceptionHandler(EmptyMediaException.class)
    public ProblemDetail handleEmptyMedia(EmptyMediaException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MediaTooLargeException.class)
    public ProblemDetail handleMediaTooLarge(MediaTooLargeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ProblemDetail handleUnsupportedMediaType(UnsupportedMediaTypeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
    }

    @ExceptionHandler(InvalidImageException.class)
    public ProblemDetail handleInvalidImage(InvalidImageException ex) {
        return ProblemType.INVALID_IMAGE.problem(ex.getMessage());
    }

    @ExceptionHandler(InfectedMediaException.class)
    public ProblemDetail handleInfectedMedia(InfectedMediaException ex) {
        return ProblemType.INFECTED_FILE.problem(ex.getMessage());
    }

    // Fail closed: an upload is never stored unscanned while the antivirus is enabled
    @ExceptionHandler(AntivirusUnavailableException.class)
    public ProblemDetail handleAntivirusUnavailable(AntivirusUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The antivirus is temporarily unavailable, try again later"
        );
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ProblemDetail handleStorageUnavailable(StorageUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }

    /**
     * The last resort: an unexpected failure is logged with its stack trace and answered as a bare 500, whose detail
     * never carries the exception's message.
     * <p>
     * Warning: method security throws {@link AccessDeniedException} from inside the controller call, so this handler
     * sees it; it is rethrown, and Spring Security still answers 403 (or 401) instead of a 500. A client that went away
     * is rethrown too, since there is no one left to answer.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException
                || DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            throw ex;
        }

        log.error("Unexpected error while handling a request", ex);

        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        // A JSON body is located with a pointer; a multipart or form object with the name of its field
        boolean jsonBody = ex.getParameter().hasParameterAnnotation(RequestBody.class);
        Locale locale = request.getLocale();

        List<InvalidValue> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    String detail = message(error, locale);

                    if (!(error instanceof FieldError field)) {
                        return InvalidValue.inBody(detail, List.of());
                    }
                    return jsonBody
                            ? InvalidValue.inBody(detail, InvalidValue.propertyPath(field.getField()))
                            : InvalidValue.inParameter(detail, field.getField());
                })
                .toList();

        return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        InvalidValue error = InvalidValue.inParameter(
                InvalidValue.expected(ex.getRequiredType()),
                ex.getPropertyName()
        );

        return handleExceptionInternal(ex, invalidRequest(List.of(error)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        InvalidValue error = InvalidValue.inParameter("is required", ex.getParameterName());

        return handleExceptionInternal(ex, invalidRequest(List.of(error)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        InvalidValue error = InvalidValue.inParameter("is required", ex.getRequestPartName());

        return handleExceptionInternal(ex, invalidRequest(List.of(error)), headers, status, request);
    }

    // A value of the wrong type in the JSON body is located with a pointer. The parser's own message names Java types
    // and positions, so it is never passed on; malformed JSON keeps the generic "Failed to read request".
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        MismatchedInputException mismatch = mismatchedInput(ex);

        if (mismatch == null || mismatch.getPath().isEmpty()) {
            return super.handleHttpMessageNotReadable(ex, headers, status, request);
        }

        InvalidValue error = InvalidValue.inBody(
                InvalidValue.expected(mismatch.getTargetType()),
                InvalidValue.jsonPath(mismatch)
        );

        return handleExceptionInternal(ex, invalidRequest(List.of(error)), headers, status, request);
    }

    private static ProblemDetail invalidRequest(List<InvalidValue> errors) {
        ProblemDetail problem = ProblemType.VALIDATION_ERROR.problem(INVALID_REQUEST);
        problem.setProperty("errors", errors.stream().sorted(InvalidValue.ORDER).toList());

        return problem;
    }

    private String message(MessageSourceResolvable error, Locale locale) {
        MessageSource messageSource = getMessageSource();

        return messageSource != null ? messageSource.getMessage(error, locale) : error.getDefaultMessage();
    }

    private static MismatchedInputException mismatchedInput(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof MismatchedInputException mismatch) {
                return mismatch;
            }
        }
        return null;
    }
}
