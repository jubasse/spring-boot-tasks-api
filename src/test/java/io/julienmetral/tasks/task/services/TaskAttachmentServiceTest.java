package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskAttachmentServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskAttachmentRepository attachmentRepository;

    @Mock
    private MediaService mediaService;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private TaskAttachmentService service;

    private final UUID taskId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Task task = new Task();
    private final Media media = new Media();
    private final MultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2, 3});

    private TaskAttachment attachment() {
        TaskAttachment attachment = new TaskAttachment();
        attachment.setId(attachmentId);
        attachment.setTask(task);
        attachment.setMedia(media);
        return attachment;
    }

    @Test
    void addStoresFileSavesAttachmentAndRecordsEvent() {
        TaskAttachment saved = attachment();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(currentUser.getId()).thenReturn(Optional.of(userId));
        when(mediaService.store(file, MediaUsage.TASK_ATTACHMENT, userId)).thenReturn(media);
        when(attachmentRepository.save(any(TaskAttachment.class))).thenReturn(saved);
        Instant before = Instant.now();

        TaskAttachment result = service.add(taskId, file);

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<TaskAttachment> captor = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepository).save(captor.capture());
        TaskAttachment toSave = captor.getValue();
        assertThat(toSave.getTask()).isSameAs(task);
        assertThat(toSave.getMedia()).isSameAs(media);
        assertThat(toSave.getComment()).isNull();
        assertThat(toSave.getCreatedAt()).isBetween(before, Instant.now());

        InOrder order = inOrder(mediaService, attachmentRepository, taskEventService);
        order.verify(mediaService).store(file, MediaUsage.TASK_ATTACHMENT, userId);
        order.verify(attachmentRepository).save(toSave);
        order.verify(taskEventService).attachmentAdded(task, saved);
    }

    @Test
    void addWithCommentLinksTheAttachmentToTheCommentWithoutLookingUpTheTask() {
        TaskComment comment = new TaskComment();
        TaskAttachment saved = attachment();
        when(currentUser.getId()).thenReturn(Optional.of(userId));
        when(mediaService.store(file, MediaUsage.TASK_ATTACHMENT, userId)).thenReturn(media);
        when(attachmentRepository.save(any(TaskAttachment.class))).thenReturn(saved);

        TaskAttachment result = service.add(task, comment, file);

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<TaskAttachment> captor = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepository).save(captor.capture());
        assertThat(captor.getValue().getTask()).isSameAs(task);
        assertThat(captor.getValue().getComment()).isSameAs(comment);
        assertThat(captor.getValue().getMedia()).isSameAs(media);
        verify(taskEventService).attachmentAdded(task, saved);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void addWithoutAuthenticatedUserStoresFileWithoutUploader() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(currentUser.getId()).thenReturn(Optional.empty());
        when(mediaService.store(file, MediaUsage.TASK_ATTACHMENT, null)).thenReturn(media);
        when(attachmentRepository.save(any(TaskAttachment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskAttachment result = service.add(taskId, file);

        assertThat(result.getMedia()).isSameAs(media);
        verify(mediaService).store(any(MultipartFile.class), any(MediaUsage.class), isNull());
    }

    @Test
    void addToUnknownTaskThrowsBeforeStoringAnything() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(taskId, file))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining(taskId.toString());
        verifyNoInteractions(mediaService, attachmentRepository, taskEventService);
    }

    @Test
    void addNeitherSavesNorRecordsWhenStorageFails() {
        StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(currentUser.getId()).thenReturn(Optional.of(userId));
        when(mediaService.store(file, MediaUsage.TASK_ATTACHMENT, userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.add(taskId, file)).isSameAs(failure);
        verifyNoInteractions(attachmentRepository, taskEventService);
    }

    @Test
    void findAllReturnsAttachmentsInCreationOrder() {
        List<TaskAttachment> attachments = List.of(attachment(), new TaskAttachment());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(attachmentRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId)).thenReturn(attachments);

        assertThat(service.findAll(taskId)).isSameAs(attachments);
    }

    @Test
    void findAllForUnknownTaskThrows() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAll(taskId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void findReturnsTheAttachmentOfTheTask() {
        TaskAttachment attachment = attachment();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(attachmentRepository.findByIdAndTaskId(attachmentId, taskId)).thenReturn(Optional.of(attachment));

        assertThat(service.find(taskId, attachmentId)).isSameAs(attachment);
    }

    @Test
    void findForUnknownTaskThrows() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(taskId, attachmentId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void findAttachmentOfAnotherTaskThrows() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(attachmentRepository.findByIdAndTaskId(attachmentId, taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(taskId, attachmentId))
                .isInstanceOf(TaskAttachmentNotFoundException.class)
                .hasMessage("Attachment not found with id: " + attachmentId);
    }

    @Test
    void removeRecordsEventBeforeDeletingAttachmentAndMedia() {
        TaskAttachment attachment = attachment();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(attachmentRepository.findByIdAndTaskId(attachmentId, taskId)).thenReturn(Optional.of(attachment));

        service.remove(taskId, attachmentId);

        InOrder order = inOrder(taskEventService, attachmentRepository, mediaService);
        order.verify(taskEventService).attachmentRemoved(task, attachment);
        order.verify(attachmentRepository).delete(attachment);
        order.verify(mediaService).delete(media);
    }

    @Test
    void removeGivenTaskAndAttachmentRecordsDeletesAndDropsTheMediaWithoutLookups() {
        TaskAttachment attachment = attachment();

        service.remove(task, attachment);

        InOrder order = inOrder(taskEventService, attachmentRepository, mediaService);
        order.verify(taskEventService).attachmentRemoved(task, attachment);
        order.verify(attachmentRepository).delete(attachment);
        order.verify(mediaService).delete(media);
        verify(attachmentRepository, never()).findByIdAndTaskId(any(), any());
        verifyNoInteractions(taskRepository);
    }

    @Test
    void removeFromUnknownTaskThrowsWithoutDeleting() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(taskId, attachmentId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(attachmentRepository, mediaService, taskEventService);
    }

    @Test
    void removeUnknownAttachmentThrowsWithoutRecordingOrDeleting() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(attachmentRepository.findByIdAndTaskId(attachmentId, taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(taskId, attachmentId))
                .isInstanceOf(TaskAttachmentNotFoundException.class);
        verify(attachmentRepository, never()).delete(any());
        verifyNoInteractions(mediaService, taskEventService);
    }
}
