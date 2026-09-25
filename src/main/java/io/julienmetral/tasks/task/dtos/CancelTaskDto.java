package io.julienmetral.tasks.task.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelTaskDto(
    @NotBlank
    @Size(max = 500)
    String reason
) {
}
