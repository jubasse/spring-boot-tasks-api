package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.identity.dtos.UserProfileResponseDto;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskPriority;
import io.julienmetral.tasks.task.entities.TaskStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TaskResponseDto(
        UUID id,
        String reference,
        String title,
        @Nullable String description,
        TaskStatus status,
        TaskPriority priority,
        @Nullable Instant dueAt,
        @Nullable Instant completedAt,
        @Nullable Instant archivedAt,
        @Nullable Instant cancelledAt,
        @Nullable String cancelledReason,
        @Nullable Instant blockedAt,
        @Nullable String blockedReason,
        long version,
        Instant updatedAt,
        @Nullable UserProfileResponseDto assignedTo,
        @Nullable UserProfileResponseDto createdBy) {

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
                UserProfileResponseDto.ofNullable(task.getAssignedTo(), mediaUrls),
                UserProfileResponseDto.ofNullable(task.getCreatedBy(), mediaUrls));
    }
}
