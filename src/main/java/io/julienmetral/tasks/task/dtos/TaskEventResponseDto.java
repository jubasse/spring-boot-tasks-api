package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserPreviewResponseDto;
import io.julienmetral.tasks.media.services.MediaUrls;
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

    public TaskEventResponseDto(TaskEvent event, MediaUrls mediaUrls) {
        this(
                event.getId(),
                event.getType(),
                UserPreviewResponseDto.of(event.getActor(), mediaUrls),
                event.getOccurredAt(),
                event.getPayload()
        );
    }
}