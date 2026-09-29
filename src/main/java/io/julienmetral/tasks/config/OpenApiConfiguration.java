package io.julienmetral.tasks.config;

import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

import static io.julienmetral.tasks.config.ProblemDocumentation.VALIDATION_PROBLEM;
import static io.julienmetral.tasks.config.ProblemDocumentation.header;
import static io.julienmetral.tasks.config.ProblemDocumentation.response;
import static io.julienmetral.tasks.config.ProblemDocumentation.withoutBody;

/**
 * The OpenAPI document served at {@code /v3/api-docs}: what springdoc cannot infer from the controllers. Rules that
 * apply to many operations are added by {@link OperationDocumentation} (from each method's annotations) and
 * {@link PathDocumentation} (from paths); a controller declares only its tag, summaries, statuses and typed problems.
 */
@Configuration
public class OpenApiConfiguration {

    static final String BEARER = "bearer";

    static final String BAD_REQUEST = "BadRequest";
    static final String UNAUTHORIZED = "Unauthorized";
    static final String FORBIDDEN = "Forbidden";
    static final String NOT_FOUND = "NotFound";
    static final String TOO_MANY_REQUESTS = "TooManyRequests";
    static final String CONTENT_TOO_LARGE = "ContentTooLarge";
    static final String UNSUPPORTED_MEDIA_TYPE = "UnsupportedMediaType";
    static final String SERVICE_UNAVAILABLE = "ServiceUnavailable";
    static final String UNEXPECTED_ERROR = "UnexpectedError";
    static final String INVALID_CREDENTIALS = "InvalidCredentials";

    private static final String REPOSITORY = "https://github.com/jubasse/spring-boot-tasks-api";

    private static final String DESCRIPTION = """
            Tasks, with their comments, files and history, for teams whose members sign up themselves.

            **Authentication.** Sign in with `POST /api/v1/auth/login`, then send the access token in an \
            `Authorization: Bearer` header. It lasts 15 minutes: `POST /api/v1/auth/refresh` trades the refresh \
            token for a new pair. Task operations also need an active account: a verified email, not disabled.

            **Errors.** Every error is a problem document (RFC 9457, `application/problem+json`), except the 401 \
            and 403 of an access check, which carry only a `WWW-Authenticate` header. When `type` is present, it \
            links to the documentation of that problem.

            **Limits.** Login, sign-up, verification email resend and password reset are rate limited: a 429 gives \
            the seconds to wait in `Retry-After`. A page holds at most 100 items.

            **Evolution.** An enumeration in a response can gain values, such as a new status: treat a value you \
            do not know as unknown rather than as an error. New optional fields can appear too.
            """;

    @Bean
    OpenAPI tasksOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();

        return new OpenAPI()
                .info(new Info()
                        .title("Tasks API")
                        .description(DESCRIPTION)
                        // build-info is written by Maven; an application started from the IDE may not have it
                        .version(build != null ? build.getVersion() : "development")
                        .contact(new Contact().name("Tasks API on GitHub").url(REPOSITORY)))
                .externalDocs(new ExternalDocumentation()
                        .description("Error responses and what to do about each")
                        .url(REPOSITORY + "/blob/main/docs/problems.md"))
                // Relative, so the document does not depend on the host that served it
                .servers(List.of(new Server().url("/")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(components());
    }

    @Bean
    OperationDocumentation operationDocumentation() {
        return new OperationDocumentation();
    }

    @Bean
    PathDocumentation pathDocumentation(
            @Value("${spring.data.web.pageable.max-page-size:2000}") int maxPageSize
    ) {
        return new PathDocumentation(maxPageSize);
    }

    private static Components components() {
        Components components = new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The access token returned by POST /api/v1/auth/login or /refresh."));

        ProblemDocumentation.schemas().forEach(components::addSchemas);

        return components
                .addResponses(BAD_REQUEST, response(
                        "Invalid request: see errors for each invalid value.",
                        VALIDATION_PROBLEM,
                        List.of(ProblemType.VALIDATION_ERROR)))
                .addResponses(UNAUTHORIZED, withoutBody(
                        "Missing, expired or invalid access token.",
                        "WWW-Authenticate",
                        "Bearer, with the reason of the refusal."))
                .addResponses(FORBIDDEN, withoutBody(
                        "The account may not do this. Task and notification operations need an active account "
                                + "(verified email, not disabled); other rules are in the operation's description.",
                        "WWW-Authenticate",
                        "Bearer error=\"insufficient_scope\"."))
                .addResponses(NOT_FOUND, response("The resource does not exist."))
                .addResponses(TOO_MANY_REQUESTS, response("Too many requests from this address or for this email.")
                        .addHeaderObject("Retry-After", header(
                                "Seconds to wait before trying again.",
                                new JsonSchema().types(Set.of("integer")))))
                .addResponses(CONTENT_TOO_LARGE, response("A file is larger than its limit."))
                .addResponses(UNSUPPORTED_MEDIA_TYPE, response(
                        "The file type is not accepted, whatever its name or declared type."))
                .addResponses(SERVICE_UNAVAILABLE, response(
                        "The antivirus or the file storage is unavailable: try again later."))
                .addResponses(UNEXPECTED_ERROR, response("An unexpected failure on the server."))
                .addResponses(INVALID_CREDENTIALS, response(
                        "Wrong email or password, or an unknown, used or revoked refresh token."));
    }

    static String responseReference(String response) {
        return "#/components/responses/" + response;
    }
}
