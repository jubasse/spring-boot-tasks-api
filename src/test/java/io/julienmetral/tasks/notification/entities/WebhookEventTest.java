package io.julienmetral.tasks.notification.entities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class WebhookEventTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void everyNotificationTypeHasExactlyOneEvent() {
        Map<WebhookEvent, List<TaskNotificationType>> typesByEvent = Arrays.stream(TaskNotificationType.values())
                .collect(Collectors.groupingBy(WebhookEvent::of));

        assertThat(typesByEvent).containsOnlyKeys(WebhookEvent.values());
        assertThat(typesByEvent.values()).allSatisfy(types -> assertThat(types).hasSize(1));
    }

    @ParameterizedTest
    @CsvSource({
            "ASSIGNED,   TASK_ASSIGNED,   task.assigned",
            "UNASSIGNED, TASK_UNASSIGNED, task.unassigned",
            "CANCELLED,  TASK_CANCELLED,  task.cancelled",
            "DELETED,    TASK_DELETED,    task.deleted",
            "COMMENTED,  TASK_COMMENTED,  task.commented",
            "MENTIONED,  TASK_MENTIONED,  task.mentioned",
            "DUE_SOON,   TASK_DUE_SOON,   task.due_soon",
            "OVERDUE,    TASK_OVERDUE,    task.overdue"
    })
    void notificationTypeMapsToItsEventAndItsDottedType(
            TaskNotificationType notificationType,
            WebhookEvent event,
            String type
    ) {
        assertThat(WebhookEvent.of(notificationType)).isEqualTo(event);
        assertThat(event.type()).isEqualTo(type);
    }

    @Test
    void typesAreDistinctLowercaseAndPrefixedWithTask() {
        Map<String, WebhookEvent> byType = Arrays.stream(WebhookEvent.values())
                .collect(Collectors.toMap(WebhookEvent::type, Function.identity()));

        assertThat(byType).hasSize(WebhookEvent.values().length);
        assertThat(byType.keySet()).allSatisfy(type -> assertThat(type).matches("task\\.[a-z_]+"));
    }

    @ParameterizedTest
    @EnumSource(WebhookEvent.class)
    void jsonFormIsTheDottedType(WebhookEvent event) {
        assertThat(jsonMapper.writeValueAsString(event)).isEqualTo("\"" + event.type() + "\"");
    }

    @ParameterizedTest
    @EnumSource(WebhookEvent.class)
    void dottedTypeIsReadBack(WebhookEvent event) {
        assertThat(jsonMapper.readValue("\"" + event.type() + "\"", WebhookEvent.class)).isEqualTo(event);
    }

    @ParameterizedTest
    @EnumSource(WebhookEvent.class)
    void constantNameIsNotAcceptedInJson(WebhookEvent event) {
        assertThatExceptionOfType(InvalidFormatException.class)
                .isThrownBy(() -> jsonMapper.readValue("\"" + event.name() + "\"", WebhookEvent.class));
    }
}
