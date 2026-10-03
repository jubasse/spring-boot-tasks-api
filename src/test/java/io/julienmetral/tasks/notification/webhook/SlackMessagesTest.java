package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.notification.entities.WebhookEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackMessagesTest {

    private static final String ACTOR = "Ada Lovelace";

    private static final String SUBJECT = "*OPS-12*: Renew the TLS certificates";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final SlackMessages slackMessages = new SlackMessages(jsonMapper);

    // Text of each event

    @Test
    void assignedNamesTheActorAndTheTask() {
        assertThat(text(WebhookEvent.TASK_ASSIGNED, data()))
                .isEqualTo("*Ada Lovelace* assigned you " + SUBJECT);
    }

    @Test
    void unassignedSaysTheTaskWentToSomeoneElse() {
        assertThat(text(WebhookEvent.TASK_UNASSIGNED, data()))
                .isEqualTo("*Ada Lovelace* assigned " + SUBJECT + " to someone else");
    }

    @Test
    void cancelledGivesTheReasonOnItsOwnLine() {
        Map<String, Object> data = data();
        data.put("reason", "Customer withdrew the request");

        assertThat(text(WebhookEvent.TASK_CANCELLED, data))
                .isEqualTo("*Ada Lovelace* cancelled " + SUBJECT + "\nReason: Customer withdrew the request");
    }

    @Test
    void cancelledWithoutAReasonHasNoReasonLine() {
        Map<String, Object> data = data();
        data.put("reason", null);

        assertThat(text(WebhookEvent.TASK_CANCELLED, data)).isEqualTo("*Ada Lovelace* cancelled " + SUBJECT);
    }

    @Test
    void deletedNamesTheActorAndTheTask() {
        assertThat(text(WebhookEvent.TASK_DELETED, data())).isEqualTo("*Ada Lovelace* deleted " + SUBJECT);
    }

    @Test
    void commentedQuotesTheExcerpt() {
        assertThat(text(WebhookEvent.TASK_COMMENTED, withComment("Looks good to me")))
                .isEqualTo("*Ada Lovelace* commented on " + SUBJECT + "\n>Looks good to me");
    }

    @Test
    void mentionedQuotesTheExcerpt() {
        assertThat(text(WebhookEvent.TASK_MENTIONED, withComment("Please review, @Grace Hopper")))
                .isEqualTo("*Ada Lovelace* mentioned you on " + SUBJECT + "\n>Please review, @Grace Hopper");
    }

    @Test
    void dueSoonGivesTheDueDateInUtcWithoutAnActor() {
        Map<String, Object> data = data();
        data.put("actor", null);
        data.put("dueAt", "2030-01-01T10:00:00Z");

        assertThat(text(WebhookEvent.TASK_DUE_SOON, data)).isEqualTo(SUBJECT + " is due on 2030-01-01 10:00 UTC");
    }

    @Test
    void overdueGivesTheDueDateInUtcWithoutAnActor() {
        Map<String, Object> data = data();
        data.put("actor", null);
        data.put("dueAt", "2030-01-01T10:00:00Z");

        assertThat(text(WebhookEvent.TASK_OVERDUE, data))
                .isEqualTo(SUBJECT + " was due on 2030-01-01 10:00 UTC and is not done yet");
    }

    @Test
    void dueDateIsCutToTheMinuteNotRounded() {
        Map<String, Object> data = data();
        data.put("dueAt", "2030-12-31T23:59:59.999999Z");

        assertThat(text(WebhookEvent.TASK_DUE_SOON, data)).endsWith(" is due on 2030-12-31 23:59 UTC");
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEvent.class, names = {"TASK_DUE_SOON", "TASK_OVERDUE"})
    void reminderNeverNamesAnActor(WebhookEvent event) {
        Map<String, Object> data = data();
        data.put("dueAt", "2030-01-01T10:00:00Z");

        assertThat(text(event, data)).doesNotContain(ACTOR).doesNotContain("Someone");
    }

    // Missing values

    @Test
    void missingActorIsSomeone() {
        Map<String, Object> data = data();
        data.put("actor", null);

        assertThat(text(WebhookEvent.TASK_DELETED, data)).isEqualTo("*Someone* deleted " + SUBJECT);
    }

    @Test
    void actorWithoutADisplayNameIsSomeone() {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", UUID.randomUUID());
        actor.put("displayName", null);
        Map<String, Object> data = data();
        data.put("actor", actor);

        assertThat(text(WebhookEvent.TASK_ASSIGNED, data)).isEqualTo("*Someone* assigned you " + SUBJECT);
    }

    // Escaping and quoting

    @Test
    void specialMentionInATitleIsEscapedSoItNotifiesNobody() {
        Map<String, Object> data = data();
        task(data).put("title", "Ship it <!channel> & <!here>");

        assertThat(text(WebhookEvent.TASK_ASSIGNED, data))
                .isEqualTo("*Ada Lovelace* assigned you *OPS-12*: Ship it &lt;!channel&gt; &amp; &lt;!here&gt;")
                .doesNotContain("<")
                .doesNotContain(">");
    }

    @Test
    void linkInATitleIsEscapedSoItIsNotALink() {
        Map<String, Object> data = data();
        task(data).put("title", "<https://evil.example.com|Open the report>");

        assertThat(text(WebhookEvent.TASK_DELETED, data))
                .endsWith("*OPS-12*: &lt;https://evil.example.com|Open the report&gt;");
    }

    @Test
    void ampersandIsEscapedOnceAndBeforeTheAngleBrackets() {
        Map<String, Object> data = data();
        task(data).put("title", "R&D: already escaped &lt; stays visible");

        assertThat(text(WebhookEvent.TASK_DELETED, data))
                .endsWith("*OPS-12*: R&amp;D: already escaped &amp;lt; stays visible");
    }

    @Test
    void referenceActorAndReasonAreEscaped() {
        Map<String, Object> data = data();
        task(data).put("reference", "<OPS>&1");
        data.put("actor", actor("<@U024BE7LH> & co"));
        data.put("reason", "Moved to <#C024BE7LR>");

        assertThat(text(WebhookEvent.TASK_CANCELLED, data)).isEqualTo(
                "*&lt;@U024BE7LH&gt; &amp; co* cancelled *&lt;OPS&gt;&amp;1*: Renew the TLS certificates"
                        + "\nReason: Moved to &lt;#C024BE7LR&gt;");
    }

    @Test
    void commentExcerptIsEscaped() {
        assertThat(text(WebhookEvent.TASK_COMMENTED, withComment("<!everyone> fixed in <https://x.example|PR> & more")))
                .endsWith("\n>&lt;!everyone&gt; fixed in &lt;https://x.example|PR&gt; &amp; more");
    }

    @Test
    void everyLineOfAMultiLineExcerptIsQuoted() {
        assertThat(text(WebhookEvent.TASK_COMMENTED, withComment("First line\nSecond line\n\nFourth line")))
                .isEqualTo("*Ada Lovelace* commented on " + SUBJECT
                        + "\n>First line\n>Second line\n>\n>Fourth line");
    }

    @Test
    void quoteMarkerAtTheStartOfAnExcerptLineIsEscapedInsideTheQuote() {
        assertThat(text(WebhookEvent.TASK_MENTIONED, withComment("> earlier message\nMy answer")))
                .endsWith("\n>&gt; earlier message\n>My answer");
    }

    // Body

    @Test
    void bodyIsAJsonObjectWithTheTextOnly() {
        String body = slackMessages.render(WebhookEvent.TASK_ASSIGNED, data());

        assertThat(body).isEqualTo("{\"text\":\"*Ada Lovelace* assigned you *OPS-12*: Renew the TLS certificates\"}");
    }

    @Test
    void quotesBackslashesAndNewlinesAreJsonEncoded() {
        Map<String, Object> data = withComment("He said \"done\" \\ really\nSure");
        task(data).put("title", "Fix \"quotes\"");

        JsonNode body = jsonMapper.readTree(slackMessages.render(WebhookEvent.TASK_COMMENTED, data));

        assertThat(body.propertyNames()).containsExactly("text");
        assertThat(body.get("text").asString())
                .isEqualTo("*Ada Lovelace* commented on *OPS-12*: Fix \"quotes\"\n>He said \"done\" \\ really\n>Sure");
    }

    @Test
    void testMessageSaysTheWebhookWorks() {
        assertThat(slackMessages.renderTest())
                .isEqualTo("{\"text\":\"Test message from the Tasks API: this Slack webhook works.\"}");
    }

    private String text(WebhookEvent event, Map<String, Object> data) {
        JsonNode body = jsonMapper.readTree(slackMessages.render(event, data));

        assertThat(body.propertyNames()).containsExactly("text");
        return body.get("text").asString();
    }

    // The shape TaskNotificationPublisher builds: task, then actor, then the details of the event
    private static Map<String, Object> data() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", UUID.randomUUID());
        task.put("reference", "OPS-12");
        task.put("title", "Renew the TLS certificates");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("actor", actor(ACTOR));
        return data;
    }

    private static Map<String, Object> actor(String displayName) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", UUID.randomUUID());
        actor.put("displayName", displayName);
        return actor;
    }

    private static Map<String, Object> withComment(String excerpt) {
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("id", UUID.randomUUID());
        comment.put("excerpt", excerpt);

        Map<String, Object> data = data();
        data.put("comment", comment);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> task(Map<String, Object> data) {
        return (Map<String, Object>) data.get("task");
    }
}
