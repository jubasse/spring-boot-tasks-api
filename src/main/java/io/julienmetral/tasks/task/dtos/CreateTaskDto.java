package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

public record CreateTaskDto(
    @NotBlank
    @Size(max = 30)
    String reference,

    @NotBlank
    @Size(max = 255)
    String title,

    String description,

    TaskPriority priority,

    Instant dueAt,

    UUID assignedTo
) {

    public CreateTaskDto {
        reference = StringUtils.hasText(reference) ? reference.trim() : null;
        title = StringUtils.hasText(title) ? title.trim() : null;
        description = StringUtils.hasText(description) ? description.trim() : null;

        if (priority == null) {
            priority = TaskPriority.MEDIUM;
        }
    }
}