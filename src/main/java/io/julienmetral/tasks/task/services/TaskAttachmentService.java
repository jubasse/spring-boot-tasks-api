package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.services.MediaService;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.entities.TaskComment;
import io.julienmetral.tasks.task.exceptions.TaskAttachmentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.repositories.TaskAttachmentRepository;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskAttachmentService {

    private final TaskRepository taskRepository;
    private final TaskAttachmentRepository attachmentRepository;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final CurrentUser currentUser;

    /** Validates (size, type, antivirus) and stores the file, then records an ATTACHMENT_ADDED event. */
    @Transactional
    public TaskAttachment add(UUID taskId, MultipartFile file) {
        return add(getTask(taskId), null, file);
    }

    /** Same as {@link #add(UUID, MultipartFile)}, for a file posted with {@code comment} (null for none). */
    @Transactional
    public TaskAttachment add(Task task, TaskComment comment, MultipartFile file) {
        Media media = mediaService.store(file, MediaUsage.TASK_ATTACHMENT, currentUser.getId().orElse(null));

        TaskAttachment attachment = new TaskAttachment();

        attachment.setTask(task);
        attachment.setComment(comment);
        attachment.setMedia(media);
        attachment.setCreatedAt(Instant.now());

        TaskAttachment saved = attachmentRepository.save(attachment);

        taskEventService.attachmentAdded(task, saved);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<TaskAttachment> findAll(UUID taskId) {
        getTask(taskId);

        return attachmentRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional(readOnly = true)
    public TaskAttachment find(UUID taskId, UUID attachmentId) {
        getTask(taskId);

        return getAttachment(taskId, attachmentId);
    }

    /** Deletes the attachment and its file (the object once the transaction commits), and records the removal. */
    @Transactional
    public void remove(UUID taskId, UUID attachmentId) {
        remove(getTask(taskId), getAttachment(taskId, attachmentId));
    }

    @Transactional
    public void remove(Task task, TaskAttachment attachment) {
        taskEventService.attachmentRemoved(task, attachment);

        attachmentRepository.delete(attachment);
        mediaService.delete(attachment.getMedia());
    }

    private Task getTask(UUID taskId) {
        return taskRepository
                .findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private TaskAttachment getAttachment(UUID taskId, UUID attachmentId) {
        return attachmentRepository
                .findByIdAndTaskId(attachmentId, taskId)
                .orElseThrow(() -> new TaskAttachmentNotFoundException(attachmentId));
    }
}
