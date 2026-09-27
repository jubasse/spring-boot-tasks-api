package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.exceptions.WebhookUrlNotAllowedException;
import org.springframework.boot.http.client.InetAddressFilter;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a webhook URL when it is declared, so its owner gets a clear refusal. The HTTP client checks the resolved
 * addresses again at every delivery (see {@code OutboundHttpConfiguration}): a host that resolves to a public
 * address today can resolve to an internal one tomorrow.
 */
public class WebhookUrlPolicy {

    private static final Set<String> SCHEMES = Set.of("http", "https");

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
