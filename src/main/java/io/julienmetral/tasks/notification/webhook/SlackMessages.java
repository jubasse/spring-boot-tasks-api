package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.entities.WebhookEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The body of a Slack incoming webhook for an event: {@code {"text": ...}} in Slack's mrkdwn, built from the same
 * data as the Standard Webhooks payload.
 */
class SlackMessages {

    // Users have no time zone yet, as in the emails
    private static final DateTimeFormatter DUE_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JsonMapper jsonMapper;

    SlackMessages(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    String render(WebhookEvent event, Map<String, Object> data) {
        Map<?, ?> task = (Map<?, ?>) data.get("task");
        String subject = "*%s*: %s".formatted(escape(task.get("reference")), escape(task.get("title")));
        String actor = "*" + actorName(data) + "*";

        String text = switch (event) {
            case TASK_ASSIGNED -> "%s assigned you %s".formatted(actor, subject);
            case TASK_UNASSIGNED -> "%s assigned %s to someone else".formatted(actor, subject);
            case TASK_CANCELLED -> "%s cancelled %s%s".formatted(actor, subject, reason(data));
            case TASK_DELETED -> "%s deleted %s".formatted(actor, subject);
            case TASK_COMMENTED -> "%s commented on %s%s".formatted(actor, subject, quote(data));
            case TASK_MENTIONED -> "%s mentioned you on %s%s".formatted(actor, subject, quote(data));
            case TASK_DUE_SOON -> "%s is due on %s".formatted(subject, dueDate(data));
            case TASK_OVERDUE -> "%s was due on %s and is not done yet".formatted(subject, dueDate(data));
        };

        return json(text);
    }

    String renderTest() {
        return json("Test message from the Tasks API: this Slack webhook works.");
    }

    private String json(String text) {
        return jsonMapper.writeValueAsString(Map.of("text", text));
    }

    private static String actorName(Map<String, Object> data) {
        Map<?, ?> actor = (Map<?, ?>) data.get("actor");
        Object name = actor == null ? null : actor.get("displayName");

        return name == null ? "Someone" : escape(name);
    }

    private static String reason(Map<String, Object> data) {
        Object reason = data.get("reason");

        return reason == null ? "" : "\nReason: " + escape(reason);
    }

    // A block quote, one ">" per line, which Slack shows as the quoted comment
    private static String quote(Map<String, Object> data) {
        Map<?, ?> comment = (Map<?, ?>) data.get("comment");

        return "\n>" + escape(comment.get("excerpt")).replace("\n", "\n>");
    }

    private static String dueDate(Map<String, Object> data) {
        return DUE_DATE.format(Instant.parse((String) data.get("dueAt")));
    }

    // Slack reads &, < and > as markup: an unescaped <!channel> in a task title would notify the whole channel
    private static String escape(Object value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
