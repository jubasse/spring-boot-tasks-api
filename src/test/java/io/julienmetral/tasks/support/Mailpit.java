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

    /** Number of emails sent to this address whose plain-text body contains the given text. */
    public int countTo(String email, String containing) {
        int count = 0;

        for (JsonNode summary : search(email).path("messages")) {
            if (text(summary).contains(containing)) {
                count++;
            }
        }

        return count;
    }

    /** Plain-text body of the most recent email sent to this address, waiting up to 5 seconds for it. */
    public String latestTextTo(String email) {
        return latestTextTo(email, "");
    }

    /**
     * Plain-text body of the most recent email sent to this address that contains the given text, waiting up to 5
     * seconds for it. Emails leave through the outbox and RabbitMQ, so two sent in a row can arrive in either order:
     * the most recent email of all is not always the one the test triggered last.
     */
    public String latestTextTo(String email, String containing) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));

        while (true) {
            Optional<String> text = findLatestTextTo(email, containing);

            if (text.isPresent()) {
                return text.get();
            }

            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No email received by " + email
                        + (containing.isEmpty() ? "" : " containing \"" + containing + "\""));
            }

            sleep();
        }
    }

    public Optional<String> findLatestTextTo(String email) {
        return findLatestTextTo(email, "");
    }

    private Optional<String> findLatestTextTo(String email, String containing) {
        // Search results are sorted newest first
        for (JsonNode summary : search(email).path("messages")) {
            String text = text(summary);

            if (text.contains(containing)) {
                return Optional.of(text);
            }
        }

        return Optional.empty();
    }

    private String text(JsonNode summary) {
        return read("/api/v1/message/{id}", summary.path("ID").asString()).path("Text").asString();
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
