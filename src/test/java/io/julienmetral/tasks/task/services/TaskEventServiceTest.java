package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.entities.TaskEventType;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.repositories.TaskEventRepository;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static io.julienmetral.tasks.support.UserSummaries.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskEventServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskEventRepository taskEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private CurrentUser currentUser;

    private TaskEventService service;

    private final Task task = new Task();
    private final User actor = new User();
    private final UserSummary actorReference = reference(ACTOR_ID);

    @BeforeEach
    void setUp() {
        service = new TaskEventService(
                taskRepository,
                taskEventRepository,
                userRepository,
                userSummaryRepository,
                currentUser,
                JsonMapper.builder().build()
        );
        actor.setId(ACTOR_ID);
    }

    private void stubActor() {
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor));
        when(userSummaryRepository.getReferenceById(ACTOR_ID)).thenReturn(actorReference);
    }

    private TaskEvent recordedEvent(Consumer<TaskEventService> action) {
        stubActor();
        Instant before = Instant.now();

        action.accept(service);

        ArgumentCaptor<TaskEvent> captor = ArgumentCaptor.forClass(TaskEvent.class);
        verify(taskEventRepository).save(captor.capture());
        TaskEvent event = captor.getValue();
        assertThat(event.getTask()).isSameAs(task);
        assertThat(event.getActor()).isSameAs(actorReference);
        assertThat(event.getOccurredAt()).isBetween(before, Instant.now());
        return event;
    }

    @Test
    void createdRecordsEventWithoutPayload() {
        TaskEvent event = recordedEvent(s -> s.created(task));

        assertThat(event.getType()).isEqualTo(TaskEventType.CREATED);
        assertThat(event.getPayload()).isNull();
    }

    @Test
    void updatedRecordsEventWithoutPayload() {
        TaskEvent event = recordedEvent(s -> s.updated(task));

        assertThat(event.getType()).isEqualTo(TaskEventType.UPDATED);
        assertThat(event.getPayload()).isNull();
    }

    @Test
    void archivedRecordsEventWithoutPayload() {
        TaskEvent event = recordedEvent(s -> s.archived(task));

        assertThat(event.getType()).isEqualTo(TaskEventType.ARCHIVED);
        assertThat(event.getPayload()).isNull();
    }

    @Test
    void unarchivedRecordsEventWithoutPayload() {
        TaskEvent event = recordedEvent(s -> s.unarchived(task));

        assertThat(event.getType()).isEqualTo(TaskEventType.UNARCHIVED);
        assertThat(event.getPayload()).isNull();
    }

    @Test
    void statusChangedRecordsFromAndTo() {
        TaskEvent event = recordedEvent(s -> s.statusChanged(task, TaskStatus.TO_DO, TaskStatus.IN_PROGRESS));

        assertThat(event.getType()).isEqualTo(TaskEventType.STATUS_CHANGED);
        assertThat(event.getPayload())
                .containsOnlyKeys("from", "to")
                .containsEntry("from", "TO_DO")
                .containsEntry("to", "IN_PROGRESS");
    }

    @Test
    void assignmentChangedRecordsUserIds() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        TaskEvent event = recordedEvent(s -> s.assignmentChanged(task, from, to));

        assertThat(event.getType()).isEqualTo(TaskEventType.ASSIGNED);
        assertThat(event.getPayload())
                .containsOnlyKeys("fromUserId", "toUserId")
                .containsEntry("fromUserId", from.toString())
                .containsEntry("toUserId", to.toString());
    }

    @Test
    void assignmentChangedFromNobodyKeepsNullFromUserId() {
        UUID to = UUID.randomUUID();

        TaskEvent event = recordedEvent(s -> s.assignmentChanged(task, null, to));

        assertThat(event.getPayload())
                .containsEntry("fromUserId", null)
                .containsEntry("toUserId", to.toString());
    }

    @Test
    void cancelledRecordsFromToAndReason() {
        TaskEvent event = recordedEvent(s -> s.cancelled(task, TaskStatus.IN_REVIEW, "duplicate"));

        assertThat(event.getType()).isEqualTo(TaskEventType.CANCELLED);
        assertThat(event.getPayload())
                .containsOnlyKeys("from", "to", "reason")
                .containsEntry("from", "IN_REVIEW")
                .containsEntry("to", "CANCELLED")
                .containsEntry("reason", "duplicate");
    }

    @Test
    void recordingWithoutAuthenticatedUserThrows() {
        when(currentUser.getId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.created(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No authenticated user");
        verifyNoInteractions(taskEventRepository, userRepository, userSummaryRepository);
    }

    @Test
    void recordingWhenAuthenticatedUserNoLongerExistsThrows() {
        when(currentUser.getId()).thenReturn(Optional.of(ACTOR_ID));
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updated(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authenticated user not found");
        verifyNoInteractions(taskEventRepository, userSummaryRepository);
    }

    @Test
    void findAllByTaskIdReturnsRepositoryPage() {
        UUID taskId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        Page<TaskEvent> page = new PageImpl<>(List.of(new TaskEvent()));
        when(taskRepository.existsById(taskId)).thenReturn(true);
        when(taskEventRepository.findAllByTaskIdOrderByOccurredAtDesc(taskId, pageable)).thenReturn(page);

        assertThat(service.findAllByTaskId(taskId, pageable)).isSameAs(page);
    }

    @Test
    void findAllByTaskIdForUnknownTaskThrows() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.existsById(taskId)).thenReturn(false);

        assertThatThrownBy(() -> service.findAllByTaskId(taskId, PageRequest.of(0, 20)))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining(taskId.toString());
        verify(taskEventRepository, org.mockito.Mockito.never())
                .findAllByTaskIdOrderByOccurredAtDesc(any(), any());
    }
}
