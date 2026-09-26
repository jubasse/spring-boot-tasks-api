package io.julienmetral.tasks.task.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of a new or edited comment, as JSON or as the {@code body} field of a multipart request. */
public record TaskCommentDto(
        @NotBlank @Size(max = 10_000) String body
) {
}
