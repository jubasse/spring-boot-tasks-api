package io.julienmetral.tasks.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the emails caught by the Mailpit test container through its HTTP API.
 * Inject it in integration tests with {@code @Import(Mailpit.class)} or {@code @Autowired} after importing it.
 */
@TestComponent
public class Mailpit {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]{43})");

    private final RestClient client;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public Mailpit(@Value("${mailpit.api-url}") String apiUrl) {
        this.client = RestClient.create(apiUrl);
    }

    /** Number of emails sent to this address. */
    public int countTo(String email) {
        return search(email).path("messages_count").asInt();
    }

    /** Plain-text body of the most recent email sent to this address, waiting up to 5 seconds for it. */
    public String latestTextTo(String email) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));

        while (true) {
            Optional<String> text = findLatestTextTo(email);

            if (text.isPresent()) {
                return text.get();
            }

            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No email received by " + email);
            }

            sleep();
        }
    }

    public Optional<String> findLatestTextTo(String email) {
        JsonNode messages = search(email).path("messages");

        if (messages.isEmpty()) {
            return Optional.empty();
        }

        // Search results are sorted newest first
        String id = messages.get(0).path("ID").asString();

        JsonNode message = read("/api/v1/message/{id}", id);

        return Optional.of(message.path("Text").asString());
    }

    /** The verification token contained in the most recent email sent to this address. */
    public String latestVerificationTokenFor(String email) {
        return extractToken(latestTextTo(email));
    }

    public static String extractToken(String text) {
        Matcher matcher = TOKEN.matcher(text);

        if (!matcher.find()) {
            throw new AssertionError("No verification token in email:\n" + text);
        }

        return matcher.group(1);
    }

    private JsonNode search(String email) {
        return read("/api/v1/search?query={query}", "to:\"" + email + "\"");
    }

    private JsonNode read(String uri, Object... variables) {
        String body = client
                .get()
                .uri(uri, variables)
                .retrieve()
                .body(String.class);

        return jsonMapper.readTree(body);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
