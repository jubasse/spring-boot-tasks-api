package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.exceptions.WebhookUrlNotAllowedException;
import org.springframework.boot.http.client.InetAddressFilter;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a webhook URL when it is declared, so its owner gets a clear refusal. The HTTP client checks the resolved
 * addresses again at every delivery (see {@code OutboundHttpConfiguration}): a host that resolves to a public
 * address today can resolve to an internal one tomorrow.
 */
public class WebhookUrlPolicy {

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private static final String SLACK_HOST = "hooks.slack.com";

    private static final String SLACK_PREFIX = "/services/";

    // The path of an incoming webhook, /services/T.../B.../..., with nothing that could be read another way: no dot
    // segment, no encoded character, no mask
    private static final Pattern SLACK_PATH = Pattern.compile("/services(/[A-Za-z0-9_-]+){2,3}");

    private static final int MAX_SLACK_URL_LENGTH = 256;

    private final InetAddressFilter addressFilter;

    private final boolean requireHttps;

    public WebhookUrlPolicy(InetAddressFilter addressFilter, boolean requireHttps) {
        this.addressFilter = addressFilter;
        this.requireHttps = requireHttps;
    }

    /** @throws WebhookUrlNotAllowedException with a reason the owner can act on */
    public void check(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!SCHEMES.contains(scheme) || uri.getHost() == null) {
            throw new WebhookUrlNotAllowedException("The URL must be an absolute http or https URL");
        }

        if (uri.getUserInfo() != null) {
            throw new WebhookUrlNotAllowedException("The URL must not contain credentials");
        }

        if (requireHttps && (!scheme.equals("https") || (uri.getPort() != -1 && uri.getPort() != 443))) {
            throw new WebhookUrlNotAllowedException("The URL must use HTTPS on port 443");
        }

        for (InetAddress address : resolve(uri.getHost())) {
            if (!addressFilter.matches(address)) {
                throw new WebhookUrlNotAllowedException("The URL must point to a public address");
            }
        }
    }

    /** Only a Slack incoming webhook URL, which leaves no destination to choose: nothing to resolve or filter. */
    public void checkSlack(String url) {
        URI uri = parse(url);

        // Real ones are about 80 characters; the bound keeps the encrypted form within the url column
        if (url.length() > MAX_SLACK_URL_LENGTH
                || !"https".equalsIgnoreCase(uri.getScheme())
                || !SLACK_HOST.equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || !SLACK_PATH.matcher(uri.getRawPath()).matches()) {
            throw new WebhookUrlNotAllowedException(
                    "A Slack webhook URL must start with https://" + SLACK_HOST + SLACK_PREFIX);
        }
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException invalid) {
            throw new WebhookUrlNotAllowedException("The URL is not valid");
        }
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw new WebhookUrlNotAllowedException("The host of the URL cannot be resolved");
        }
    }
}
