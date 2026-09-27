package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.dtos.TaskAttachmentResponseDto;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.security.AllowedRolesOrAssignedToOnly;
import io.julienmetral.tasks.task.security.AllowedRolesOrUploaderOnly;
import io.julienmetral.tasks.task.services.TaskAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

// The task path variable is named id: @AllowedRolesOrAssignedToOnly reads #id
@RestController
@RequestMapping("/api/v1/tasks/{id}/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "Files attached to a task.")
public class TaskAttachmentController {

    private final TaskAttachmentService attachmentService;
    private final MediaUrls mediaUrls;

    @Operation(summary = "Attach a file to a task")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskAttachmentResponseDto> addAttachment(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file
    ) {
        TaskAttachment attachment = attachmentService.add(id, file);

        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + id + "/attachments/" + attachment.getId()))
                .body(response(attachment));
    }

    @Operation(summary = "List the files of a task")
    @GetMapping
    public List<TaskAttachmentResponseDto> listAttachments(
            @PathVariable UUID id
    ) {
        return attachmentService
                .findAll(id)
                .stream()
                .map(this::response)
                .toList();
    }

    @Operation(summary = "Get a file and its download URL")
    @GetMapping("/{attachmentId}")
    public TaskAttachmentResponseDto getAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId
    ) {
        return response(attachmentService.find(id, attachmentId));
    }

    @Operation(summary = "Remove a file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{attachmentId}")
    @AllowedRolesOrUploaderOnly(UserRole.ADMIN)
    public ResponseEntity<Void> removeAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId
    ) {
        attachmentService.remove(id, attachmentId);

        return ResponseEntity
                .noContent()
                .build();
    }

    private TaskAttachmentResponseDto response(TaskAttachment attachment) {
        return new TaskAttachmentResponseDto(attachment, mediaUrls);
    }
}
