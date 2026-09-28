package io.julienmetral.tasks.notification.entities;

public enum WebhookDisabledReason {
    OWNER,
    // The receiver answered 410 Gone, the Standard Webhooks way of asking to stop
    GONE,
    // Every attempt failed for webhooks.disable-after
    FAILING
}
