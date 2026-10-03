package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.exceptions.WebhookUrlNotAllowedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.InetAddressFilter;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Every host is an IP literal, {@code localhost} or a {@code .invalid} name (RFC 6761), so no test depends on the
 * machine's network.
 */
class WebhookUrlPolicyTest {

    private static final String NOT_VALID = "The URL is not valid";

    private static final String NOT_HTTP = "The URL must be an absolute http or https URL";

    private static final String CREDENTIALS = "The URL must not contain credentials";

    private static final String NOT_HTTPS_443 = "The URL must use HTTPS on port 443";

    private static final String UNRESOLVED = "The host of the URL cannot be resolved";

    private static final String NOT_PUBLIC = "The URL must point to a public address";

    private static final String NOT_SLACK = "A Slack webhook URL must start with https://hooks.slack.com/services/";

    private final WebhookUrlPolicy httpsOnly = new WebhookUrlPolicy(InetAddressFilter.externalAddresses(), true);

    private final WebhookUrlPolicy localReceiverAllowed = new WebhookUrlPolicy(
            InetAddressFilter.externalAddresses().or("127.0.0.1/32"),
            false
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "https://93.184.216.34/",
            "https://93.184.216.34/hooks/tasks?source=api",
            "https://93.184.216.34:443/hooks",
            "HTTPS://93.184.216.34/hooks",
            "https://[2606:4700:4700::1111]/hooks"
    })
    void publicHttpsUrlOnTheDefaultPortIsAccepted(String url) {
        assertThatCode(() -> httpsOnly.check(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34/hooks",
            "http://93.184.216.34:443/hooks",
            "https://93.184.216.34:8443/hooks",
            "https://93.184.216.34:80/hooks"
    })
    void urlThatIsNotHttpsOnPort443IsRefusedWhenHttpsIsRequired(String url) {
        assertRefused(httpsOnly, url, NOT_HTTPS_443);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34/hooks",
            "http://93.184.216.34:8080/hooks",
            "https://93.184.216.34:8443/hooks",
            "https://93.184.216.34/hooks"
    })
    void publicUrlOnAnyPortAndSchemeIsAcceptedWhenHttpsIsNotRequired(String url) {
        assertThatCode(() -> localReceiverAllowed.check(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080/hooks", "http://127.0.0.1/"})
    void addressAddedToTheFilterIsAccepted(String url) {
        assertThatCode(() -> localReceiverAllowed.check(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.2:8080/hooks",
            "http://10.0.0.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]:8080/hooks"
    })
    void addressOutsideTheAddedRangeIsStillRefused(String url) {
        assertRefused(localReceiverAllowed, url, NOT_PUBLIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://10.0.0.1/",
            "https://172.16.0.1/",
            "https://192.168.1.1/",
            "https://100.64.0.1/",
            "https://169.254.169.254/latest/meta-data/",
            "https://127.0.0.1/",
            "https://0.0.0.0/",
            "https://[::1]/",
            "https://[fe80::1]/",
            "https://[fd00:ec2::254]/",
            "https://[::ffff:127.0.0.1]/",
            "https://localhost/"
    })
    void privateLoopbackOrLinkLocalAddressIsRefused(String url) {
        assertRefused(httpsOnly, url, NOT_PUBLIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://93.184.216.34/hooks",
            "file:///etc/passwd",
            "mailto:ops@example.com",
            "javascript:alert(1)",
            "/api/v1/hooks",
            "93.184.216.34/hooks",
            "//93.184.216.34/hooks",
            "https:///hooks",
            "https:hooks"
    })
    void urlThatIsNotAnAbsoluteHttpUrlIsRefused(String url) {
        assertRefused(httpsOnly, url, NOT_HTTP);
        assertRefused(localReceiverAllowed, url, NOT_HTTP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://93.184.216.34/hooks with spaces", "https://[::1/", "https://", "https://%zz/"})
    void urlThatCannotBeParsedIsRefused(String url) {
        assertRefused(httpsOnly, url, NOT_VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:secret@93.184.216.34/hooks",
            "https://user@93.184.216.34/hooks",
            "https://:secret@93.184.216.34/hooks"
    })
    void urlWithCredentialsIsRefused(String url) {
        assertRefused(httpsOnly, url, CREDENTIALS);
        assertRefused(localReceiverAllowed, url, CREDENTIALS);
    }

    @Test
    void credentialsAreReportedBeforeTheMissingHttps() {
        assertRefused(httpsOnly, "http://user:secret@93.184.216.34/hooks", CREDENTIALS);
    }

    @Test
    void missingHttpsIsReportedBeforeTheHostIsResolved() {
        assertRefused(httpsOnly, "http://webhook-receiver.invalid/hooks", NOT_HTTPS_443);
    }

    @Test
    void hostThatCannotBeResolvedIsRefused() {
        assertRefused(httpsOnly, "https://webhook-receiver.invalid/hooks", UNRESOLVED);
        assertRefused(localReceiverAllowed, "http://webhook-receiver.invalid/hooks", UNRESOLVED);
    }

    @Test
    void everyAddressTheHostResolvesToIsChecked() throws Exception {
        List<InetAddress> checked = new ArrayList<>();
        WebhookUrlPolicy recording = new WebhookUrlPolicy(InetAddressFilter.adapt(checked::add), true);

        recording.check("https://localhost/hooks");

        assertThat(checked).containsExactly(InetAddress.getAllByName("localhost"));
    }

    @Test
    void hostWithOneRefusedAddressAmongItsAddressesIsRefused() throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName("localhost");
        InetAddress last = addresses[addresses.length - 1];
        WebhookUrlPolicy refusingTheLast = new WebhookUrlPolicy(
                InetAddressFilter.adapt(address -> !address.equals(last)),
                true
        );

        assertRefused(refusingTheLast, "https://localhost/hooks", NOT_PUBLIC);
    }

    @Test
    void filterIsNotConsultedForAUrlRefusedEarlier() {
        List<InetAddress> checked = new ArrayList<>();
        WebhookUrlPolicy recording = new WebhookUrlPolicy(InetAddressFilter.adapt(checked::add), true);

        assertRefused(recording, "http://127.0.0.1/hooks", NOT_HTTPS_443);
        assertRefused(recording, "https://user@127.0.0.1/hooks", CREDENTIALS);
        assertThat(checked).isEmpty();
    }

    // Slack incoming webhooks

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hooks.slack.com/services/T0001/B0002/test-token",
            "https://hooks.slack.com:443/services/T0001/B0002/test-token",
            "HTTPS://HOOKS.SLACK.COM/services/T0001/B0002/test-token"
    })
    void slackIncomingWebhookUrlIsAccepted(String url) {
        assertThatCode(() -> httpsOnly.checkSlack(url)).as(url).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.slack.com/services/T0001/B0002/test-token",
            "ftp://hooks.slack.com/services/T0001/B0002/test-token",
            "hooks.slack.com/services/T0001/B0002/test-token",
            "//hooks.slack.com/services/T0001/B0002/test-token",
            "https://evil.hooks.slack.com.example.com/services/T0001/B0002/test-token",
            "https://hooks.slack.com.example.com/services/T0001/B0002/test-token",
            "https://evil.hooks.slack.com/services/T0001/B0002/test-token",
            "https://slack.com/services/T0001/B0002/test-token",
            "https://hooks.slack.com./services/T0001/B0002/test-token",
            "https://3.33.1.1/services/T0001/B0002/test-token",
            "https://hooks.slack.com@evil.example.com/services/T0001/B0002/test-token",
            "https://hooks.slack.com/workflows/T0001/B0002/test-token",
            "https://hooks.slack.com/api/services/T0001/B0002/test-token",
            "https://hooks.slack.com/services",
            "https://hooks.slack.com/",
            "https://hooks.slack.com",
            "https://hooks.slack.com/services/T0001/B0002/test-token?channel=general",
            "https://hooks.slack.com/services/T0001/B0002/test-token?",
            "https://user:secret@hooks.slack.com/services/T0001/B0002/test-token",
            "https://user@hooks.slack.com/services/T0001/B0002/test-token",
            "https://hooks.slack.com:8443/services/T0001/B0002/test-token",
            "https://hooks.slack.com:80/services/T0001/B0002/test-token",
            "https://hooks.slack.com/services/",
            "https://hooks.slack.com/services/T0001",
            "https://hooks.slack.com/services/****",
            "https://hooks.slack.com/services/T0001/B0002/test-token#channel",
            "https://hooks.slack.com/services/../api/T0001/B0002",
            "https://hooks.slack.com/services/T0001/../B0002/test-token",
            "https://hooks.slack.com/services/T0001/B0002/test%2Ftoken",
            "https://hooks.slack.com/services/T0001/B0002/test-token/extra/segment"
    })
    void urlThatIsNotASlackIncomingWebhookIsRefused(String url) {
        assertSlackRefused(httpsOnly, url, NOT_SLACK);
    }

    @Test
    void slackUrlMustUseHttpsEvenWhenHttpsIsNotRequiredForOtherWebhooks() {
        assertSlackRefused(localReceiverAllowed, "http://hooks.slack.com/services/T0001/B0002/test-token", NOT_SLACK);
    }

    @Test
    void slackUrlOf256CharactersIsAcceptedAndOneOf257IsRefused() {
        String prefix = "https://hooks.slack.com/services/T0001/B0002/";
        String longest = prefix + "a".repeat(256 - prefix.length());

        assertThatCode(() -> httpsOnly.checkSlack(longest)).doesNotThrowAnyException();
        assertSlackRefused(httpsOnly, longest + "a", NOT_SLACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hooks.slack.com/services/T0001/B0002/test token",
            "https://hooks.slack.com/services/%zz",
            "https://"
    })
    void slackUrlThatCannotBeParsedIsRefusedAsInvalid(String url) {
        assertSlackRefused(httpsOnly, url, NOT_VALID);
    }

    @Test
    void slackUrlIsNeitherResolvedNorFiltered() {
        List<InetAddress> checked = new ArrayList<>();
        WebhookUrlPolicy refusingEverything = new WebhookUrlPolicy(InetAddressFilter.adapt(address -> {
            checked.add(address);
            return false;
        }), true);

        assertThatCode(() -> refusingEverything.checkSlack("https://hooks.slack.com/services/T0001/B0002/test-token"))
                .doesNotThrowAnyException();
        assertThat(checked).isEmpty();
    }

    private static void assertRefused(WebhookUrlPolicy policy, String url, String reason) {
        assertThatExceptionOfType(WebhookUrlNotAllowedException.class)
                .as(url)
                .isThrownBy(() -> policy.check(url))
                .withMessage(reason);
    }

    private static void assertSlackRefused(WebhookUrlPolicy policy, String url, String reason) {
        assertThatExceptionOfType(WebhookUrlNotAllowedException.class)
                .as(url)
                .isThrownBy(() -> policy.checkSlack(url))
                .withMessage(reason);
    }
}
