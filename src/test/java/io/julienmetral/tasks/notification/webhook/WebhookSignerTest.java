package io.julienmetral.tasks.notification.webhook;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class WebhookSignerTest {

    // Test-only secrets; the first is the example of the Standard Webhooks test suite
    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"; // gitleaks:allow

    private static final String PREVIOUS_SECRET = "whsec_QmVmb3JlIHRoZSByb3RhdGlvbjogMzIgYnl0ZXMuLi4="; // gitleaks:allow

    private static final String OTHER_SECRET = "whsec_U29tZWJvZHkgZWxzZSBzZWNyZXQgb2YgMzIgYnl0ZXM="; // gitleaks:allow

    private static final String MESSAGE_ID = "0199c1a4-7e2b-7c3d-9f00-123456789abc";

    private static final String BODY = "{\"type\":\"task.assigned\",\"data\":{\"title\":\"Déjà vu\"}}";

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void headersCarryTheMessageIdTheTimestampInSecondsAndOneSignature() {
        Instant timestamp = Instant.parse("2026-09-28T10:11:12.987654321Z");

        Map<String, String> headers = signer.headers(MESSAGE_ID, timestamp, bytes(BODY), List.of(SECRET));

        assertThat(headers).containsOnlyKeys("webhook-id", "webhook-timestamp", "webhook-signature");
        assertThat(headers.get("webhook-id")).isEqualTo(MESSAGE_ID);
        assertThat(headers.get("webhook-timestamp")).isEqualTo(String.valueOf(timestamp.getEpochSecond()));
        assertThat(headers.get("webhook-signature")).matches("v1,[A-Za-z0-9+/]{43}=");
    }

    @Test
    void signatureMatchesTheExampleOfTheStandardWebhooksTestSuite() {
        Map<String, String> headers = signer.headers("msg_p5jXN8AQM9LWM0D4loKWxJek",
                Instant.ofEpochSecond(1614265330), bytes("{\"test\": 2432232314}"), List.of(SECRET));

        assertThat(headers.get("webhook-signature")).isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void signatureIsTheOneTheOfficialLibraryComputes() throws Exception {
        Instant timestamp = Instant.parse("2031-02-03T04:05:06Z");

        Map<String, String> headers = signer.headers(MESSAGE_ID, timestamp, bytes(BODY), List.of(SECRET));

        assertThat(headers.get("webhook-signature"))
                .isEqualTo(new Webhook(SECRET).sign(MESSAGE_ID, timestamp.getEpochSecond(), BODY));
    }

    @Test
    void officialLibraryAcceptsTheHeadersOfAFreshMessage() {
        Map<String, String> headers = signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET));

        assertThatNoException().isThrownBy(() -> new Webhook(SECRET).verify(BODY, multimap(headers)));
    }

    @Test
    void twoSecretsGiveTwoSignaturesThatTheLibraryAcceptsWithEitherSecret() {
        Map<String, String> headers =
                signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET, PREVIOUS_SECRET));

        String[] signatures = headers.get("webhook-signature").split(" ");
        assertThat(signatures).hasSize(2).doesNotHaveDuplicates().allMatch(signature -> signature.startsWith("v1,"));
        assertThatNoException().isThrownBy(() -> new Webhook(SECRET).verify(BODY, multimap(headers)));
        assertThatNoException().isThrownBy(() -> new Webhook(PREVIOUS_SECRET).verify(BODY, multimap(headers)));
    }

    @Test
    void signaturesComeInTheOrderOfTheSecrets() throws Exception {
        Instant timestamp = Instant.parse("2031-02-03T04:05:06Z");

        Map<String, String> headers =
                signer.headers(MESSAGE_ID, timestamp, bytes(BODY), List.of(SECRET, PREVIOUS_SECRET));

        assertThat(headers.get("webhook-signature")).isEqualTo(
                new Webhook(SECRET).sign(MESSAGE_ID, timestamp.getEpochSecond(), BODY) + " "
                        + new Webhook(PREVIOUS_SECRET).sign(MESSAGE_ID, timestamp.getEpochSecond(), BODY));
    }

    @Test
    void anotherSecretIsRejected() {
        Map<String, String> headers =
                signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET, PREVIOUS_SECRET));

        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(OTHER_SECRET).verify(BODY, multimap(headers)));
    }

    @Test
    void tamperedBodyIsRejected() {
        Map<String, String> headers = signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET));

        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(SECRET).verify(BODY.replace("Déjà", "Deja"), multimap(headers)))
                .withMessage("No matching signature found");
    }

    @Test
    void tamperedTimestampIsRejected() {
        Map<String, String> headers = new HashMap<>(
                signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET)));
        headers.put("webhook-timestamp", String.valueOf(Long.parseLong(headers.get("webhook-timestamp")) + 1));

        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(SECRET).verify(BODY, multimap(headers)))
                .withMessage("No matching signature found");
    }

    @Test
    void tamperedMessageIdIsRejected() {
        Map<String, String> headers = new HashMap<>(
                signer.headers(MESSAGE_ID, Instant.now(), bytes(BODY), List.of(SECRET)));
        headers.put("webhook-id", MESSAGE_ID.replace('a', 'b'));

        assertThatExceptionOfType(WebhookVerificationException.class)
                .isThrownBy(() -> new Webhook(SECRET).verify(BODY, multimap(headers)))
                .withMessage("No matching signature found");
    }

    @Test
    void sameMessageSignedAtAnotherSecondGetsAnotherSignature() {
        Instant first = Instant.parse("2031-02-03T04:05:06Z");

        String signature = signer.headers(MESSAGE_ID, first, bytes(BODY), List.of(SECRET)).get("webhook-signature");
        String retried = signer.headers(MESSAGE_ID, first.plusSeconds(5), bytes(BODY), List.of(SECRET))
                .get("webhook-signature");
        String sameSecond = signer.headers(MESSAGE_ID, first.plusMillis(999), bytes(BODY), List.of(SECRET))
                .get("webhook-signature");

        assertThat(retried).isNotEqualTo(signature);
        assertThat(sameSecond).isEqualTo(signature);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> multimap(Map<String, String> headers) {
        Map<String, List<String>> multimap = new HashMap<>();
        headers.forEach((name, value) -> multimap.put(name, List.of(value)));
        return multimap;
    }
}
