package io.julienmetral.tasks.notification.entities;

public enum WebhookKind {
    // A signed Standard Webhooks JSON event
    WEBHOOK,
    // A message for a Slack incoming webhook; its URL is a credential, so it is encrypted and never shown
    SLACK
}
