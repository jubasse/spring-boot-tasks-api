package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.task.dtos.TaskCommentDto;
import io.julienmetral.tasks.task.dtos.TaskCommentResponseDto;
import io.julienmetral.tasks.task.entities.TaskComment;
import io.julienmetral.tasks.task.security.AllowedRolesOrCommentAuthorOnly;
import io.julienmetral.tasks.task.security.CommentAuthorOnly;
import io.julienmetral.tasks.task.services.TaskCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks/{id}/comments")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "Comments on a task, with mentions and files.")
public class TaskCommentController {

    private final TaskCommentService commentService;
    private final MediaUrls mediaUrls;

    @Operation(operationId = "addComment", summary = "Comment on a task, as JSON or as a form with up to 5 files")
    @ResponseStatus(HttpStatus.CREATED)
    @DocumentedProblems(ProblemType.INVALID_MENTION)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskCommentResponseDto> addComment(
            @PathVariable UUID id,
            @Valid @RequestBody TaskCommentDto dto
    ) {
        return created(id, commentService.add(id, dto.body(), List.of()));
    }

    @Operation(operationId = "addComment", summary = "Comment on a task, as JSON or as a form with up to 5 files")
    @ResponseStatus(HttpStatus.CREATED)
    @DocumentedProblems(ProblemType.INVALID_MENTION)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCommentResponseDto> addCommentWithFiles(
            @PathVariable UUID id,
            @Valid @ModelAttribute TaskCommentDto dto,
            @RequestPart(name = "files", required = false) List<MultipartFile> files
    ) {
        return created(id, commentService.add(id, dto.body(), files == null ? List.of() : files));
    }

    @Operation(summary = "List the comments of a task, oldest first")
    @GetMapping
    public PagedModel<TaskCommentResponseDto> listComments(
            @PathVariable UUID id,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return new PagedModel<>(commentService.findAll(id, pageable).map(this::response));
    }

    @Operation(summary = "Get a comment")
    @GetMapping("/{commentId}")
    public TaskCommentResponseDto getComment(
            @PathVariable UUID id,
            @PathVariable UUID commentId
    ) {
        return response(commentService.find(id, commentId));
    }

    @Operation(summary = "Edit a comment")
    @DocumentedProblems(ProblemType.INVALID_MENTION)
    @PatchMapping("/{commentId}")
    @CommentAuthorOnly
    public TaskCommentResponseDto editComment(
            @PathVariable UUID id,
            @PathVariable UUID commentId,
            @Valid @RequestBody TaskCommentDto dto
    ) {
        return response(commentService.edit(id, commentId, dto.body()));
    }

    @Operation(summary = "Delete a comment and its files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{commentId}")
    @AllowedRolesOrCommentAuthorOnly(UserRole.ADMIN)
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID id,
            @PathVariable UUID commentId
    ) {
        commentService.delete(id, commentId);

        return ResponseEntity
                .noContent()
                .build();
    }

    private ResponseEntity<TaskCommentResponseDto> created(UUID taskId, TaskComment comment) {
        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + taskId + "/comments/" + comment.getId()))
                .body(response(comment));
    }

    private TaskCommentResponseDto response(TaskComment comment) {
        return new TaskCommentResponseDto(comment, mediaUrls);
    }
}
