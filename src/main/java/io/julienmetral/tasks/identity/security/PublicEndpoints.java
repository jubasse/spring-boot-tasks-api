package io.julienmetral.tasks.identity.security;

import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * The API endpoints anyone can call without an access token. {@link SecurityConfiguration} permits them and the API
 * documentation marks them as public ({@code OpenApiConfiguration}), from this one list, so that the two cannot
 * disagree.
 */
public final class PublicEndpoints {

    public record Endpoint(HttpMethod method, String pattern) {
    }

    public static final List<Endpoint> ALL = List.of(
            new Endpoint(HttpMethod.POST, "/api/v1/users"),
            // Login, token refresh and logout carry their own credentials; email verification and password reset
            // carry the token received by email
            new Endpoint(HttpMethod.POST, "/api/v1/auth/login"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/refresh"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/logout"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/verify-email"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/password-reset/request"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/password-reset/confirm"),
            // Identicons are images loaded by <img> tags, which send no token
            new Endpoint(HttpMethod.GET, "/api/v1/identicons/*")
    );

    private PublicEndpoints() {
    }
}
