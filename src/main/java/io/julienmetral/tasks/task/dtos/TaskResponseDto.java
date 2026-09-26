package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserProfileResponseDto;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskPriority;
import io.julienmetral.tasks.task.entities.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskResponseDto(
    UUID id,
    String reference,
    String title,
    String description,
    TaskStatus status,
    TaskPriority priority,
    Instant dueAt,
    Instant completedAt,
    Instant archivedAt,
    Instant cancelledAt,
    String cancelledReason,
    Instant blockedAt,
    String blockedReason,
    long version,
    Instant updatedAt,
    UserProfileResponseDto assignedTo,
    UserProfileResponseDto createdBy
) {

    public TaskResponseDto(Task task, MediaUrls mediaUrls) {
        this(
            task.getId(),
            task.getReference(),
            task.getTitle(),
            task.getDescription(),
            task.getStatus(),
            task.getPriority(),
            task.getDueAt(),
            task.getCompletedAt(),
            task.getArchivedAt(),
            task.getCancelledAt(),
            task.getCancelledReason(),
            task.getBlockedAt(),
            task.getBlockedReason(),
            task.getVersion(),
            task.getUpdatedAt(),
            UserProfileResponseDto.of(task.getAssignedTo(), mediaUrls),
            UserProfileResponseDto.of(task.getCreatedBy(), mediaUrls)
        );
    }
}
