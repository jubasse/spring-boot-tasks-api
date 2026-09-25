package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskPriority;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

import java.time.Instant;

public record UpdateTaskDto(
    @Size(max = 255)
    String title,

    String description,

    TaskPriority priority,

    Instant dueAt
) {

    public UpdateTaskDto {
        title = StringUtils.hasText(title) ? title.trim() : null;
        description = StringUtils.hasText(description) ? description.trim() : null;
    }
}
