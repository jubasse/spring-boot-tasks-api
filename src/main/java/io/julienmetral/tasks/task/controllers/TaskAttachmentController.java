package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.dtos.TaskAttachmentResponseDto;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.security.AllowedRolesOrAssignedToOnly;
import io.julienmetral.tasks.task.security.AllowedRolesOrUploaderOnly;
import io.julienmetral.tasks.task.services.TaskAttachmentService;
import lombok.RequiredArgsConstructor;
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
public class TaskAttachmentController {

    private final TaskAttachmentService attachmentService;
    private final MediaUrls mediaUrls;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskAttachmentResponseDto> add(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file
    ) {
        TaskAttachment attachment = attachmentService.add(id, file);

        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + id + "/attachments/" + attachment.getId()))
                .body(response(attachment));
    }

    @GetMapping
    public List<TaskAttachmentResponseDto> findAll(
            @PathVariable UUID id
    ) {
        return attachmentService
                .findAll(id)
                .stream()
                .map(this::response)
                .toList();
    }

    @GetMapping("/{attachmentId}")
    public TaskAttachmentResponseDto find(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId
    ) {
        return response(attachmentService.find(id, attachmentId));
    }

    @DeleteMapping("/{attachmentId}")
    @AllowedRolesOrUploaderOnly(UserRole.ADMIN)
    public ResponseEntity<Void> remove(
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
