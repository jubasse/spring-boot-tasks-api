package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.dtos.TaskCommentDto;
import io.julienmetral.tasks.task.dtos.TaskCommentResponseDto;
import io.julienmetral.tasks.task.entities.TaskComment;
import io.julienmetral.tasks.task.security.AllowedRolesOrCommentAuthorOnly;
import io.julienmetral.tasks.task.security.CommentAuthorOnly;
import io.julienmetral.tasks.task.services.TaskCommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
public class TaskCommentController {

    private final TaskCommentService commentService;
    private final MediaUrls mediaUrls;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskCommentResponseDto> add(
            @PathVariable UUID id,
            @Valid @RequestBody TaskCommentDto dto
    ) {
        return created(id, commentService.add(id, dto.body(), List.of()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskCommentResponseDto> addWithFiles(
            @PathVariable UUID id,
            @Valid @ModelAttribute TaskCommentDto dto,
            @RequestPart(name = "files", required = false) List<MultipartFile> files
    ) {
        return created(id, commentService.add(id, dto.body(), files == null ? List.of() : files));
    }

    @GetMapping
    public Page<TaskCommentResponseDto> findAll(
            @PathVariable UUID id,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return commentService
                .findAll(id, pageable)
                .map(this::response);
    }

    @GetMapping("/{commentId}")
    public TaskCommentResponseDto find(
            @PathVariable UUID id,
            @PathVariable UUID commentId
    ) {
        return response(commentService.find(id, commentId));
    }

    @PatchMapping("/{commentId}")
    @CommentAuthorOnly
    public TaskCommentResponseDto edit(
            @PathVariable UUID id,
            @PathVariable UUID commentId,
            @Valid @RequestBody TaskCommentDto dto
    ) {
        return response(commentService.edit(id, commentId, dto.body()));
    }

    @DeleteMapping("/{commentId}")
    @AllowedRolesOrCommentAuthorOnly(UserRole.ADMIN)
    public ResponseEntity<Void> delete(
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
