package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TaskEventResponseDto(
        UUID id,
        TaskEventType type,
        UserPreviewResponseDto actor,
        Instant occurredAt,
        Map<String, Object> payload
) {

    public TaskEventResponseDto(TaskEvent event) {
        this(
                event.getId(),
                event.getType(),
                event.getActor() != null
                        ? new UserPreviewResponseDto(event.getActor())
                        : null,
                event.getOccurredAt(),
                event.getPayload()
        );
    }
}