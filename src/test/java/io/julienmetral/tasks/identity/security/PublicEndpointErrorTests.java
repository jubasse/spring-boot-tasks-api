package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Over real HTTP: MockMvc never makes the container's error dispatch to {@code /error}, which is where a public
 * endpoint's 400 once became a 401. The configuration is the one of {@code MonitoringTests}, so both classes share
 * one cached context and its containers.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")
class PublicEndpointErrorTests {

    private static final String MALFORMED_JSON = "{\"email\": ";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void malformedJsonOnLoginGivesBadRequest() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/auth/login", MALFORMED_JSON, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void malformedJsonOnSignUpGivesBadRequest() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/users", MALFORMED_JSON, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void invalidIdenticonIdGivesBadRequest() throws Exception {
        HttpResponse<String> response = get("/api/v1/identicons/not-a-uuid", null);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void identiconIsServedWithoutAToken() throws Exception {
        HttpResponse<String> response = get("/api/v1/identicons/" + UUID.randomUUID(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("image/svg+xml"));
    }

    @Test
    void protectedEndpointsStillAnswerUnauthorizedWithoutAToken() throws Exception {
        assertThat(get("/api/v1/tasks", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/users/" + UUID.randomUUID(), null).statusCode()).isEqualTo(401);
        assertThat(postJson("/api/v1/tasks", "{}", null).statusCode()).isEqualTo(401);
    }

    @Test
    void malformedJsonOnAProtectedEndpointWithoutATokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/tasks", MALFORMED_JSON, null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void invalidTokenOnAProtectedEndpointIsUnauthorized() throws Exception {
        assertThat(get("/api/v1/tasks", "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void unknownPathWithoutATokenIsUnauthorized() throws Exception {
        assertThat(get("/api/v1/does-not-exist", null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(request(path, bearer).GET());
    }

    private HttpResponse<String> postJson(String path, String body, String bearer) throws Exception {
        return send(request(path, bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));

        return bearer == null ? builder : builder.header("Authorization", "Bearer " + bearer);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
