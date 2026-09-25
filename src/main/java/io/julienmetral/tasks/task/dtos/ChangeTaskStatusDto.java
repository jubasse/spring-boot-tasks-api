package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeTaskStatusDto(
    @NotNull TaskStatus status
) {
}