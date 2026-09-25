package io.julienmetral.tasks.task.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignTaskDto(
    @NotNull UUID userId
) {
}
