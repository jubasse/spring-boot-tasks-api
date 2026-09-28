package io.julienmetral.tasks.shared.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * The problem types a client can act on beyond the status code (RFC 9457): domain rules and invalid input. Generic
 * HTTP conditions (not found, unauthorized, too large, rate limited, unavailable) keep {@code about:blank}, whose
 * title is the status phrase. Each type URI points to its section of {@code docs/problems.md}.
 * <p>
 * Warning: a type URI and its title are what clients match on. Renaming a type, changing its title or moving the
 * document is a breaking change.
 */
public enum ProblemType {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation-error", "Invalid request"),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "invalid-token", "Invalid or expired token"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "Email already in use"),
    REFERENCE_TAKEN(HttpStatus.CONFLICT, "reference-taken", "Task reference already in use"),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "email-already-verified", "Email already verified"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "version-conflict", "Changed by another request"),
    ASSIGNEE_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "assignee-not-active", "Assignee not active"),
    INVALID_MENTION(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-mention", "Invalid mention"),
    INFECTED_FILE(HttpStatus.UNPROCESSABLE_CONTENT, "infected-file", "File rejected by the antivirus"),
    INVALID_IMAGE(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-image", "Invalid image"),
    WEBHOOK_URL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "webhook-url-not-allowed", "Webhook URL not allowed"),
    WEBHOOK_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "webhook-limit-reached", "Webhook limit reached"),
    WEBHOOK_NOT_SIGNED(HttpStatus.UNPROCESSABLE_CONTENT, "webhook-not-signed", "Webhook not signed");

    private static final String DOCUMENT =
            "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md#";

    private final HttpStatus status;

    private final String slug;

    private final URI type;

    private final String title;

    ProblemType(HttpStatus status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.type = URI.create(DOCUMENT + slug);
        this.title = title;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);

        return problem;
    }
}
