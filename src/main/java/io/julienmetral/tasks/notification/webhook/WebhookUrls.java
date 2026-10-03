package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookKind;

/** The URL of an endpoint as responses show it: a Slack URL is its credential, so it is never shown. */
public final class WebhookUrls {

    public static final String SLACK_MASK = "https://hooks.slack.com/services/****";

    private WebhookUrls() {
    }

    public static String shown(WebhookEndpoint endpoint) {
        return endpoint.getKind() == WebhookKind.SLACK ? SLACK_MASK : endpoint.getUrl();
    }
}
