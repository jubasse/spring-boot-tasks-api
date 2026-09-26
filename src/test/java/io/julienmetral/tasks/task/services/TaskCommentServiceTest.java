package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskAttachment;
import io.julienmetral.tasks.task.entities.TaskComment;
import io.julienmetral.tasks.task.events.TaskCommentAdded;
import io.julienmetral.tasks.task.events.UsersMentionedInComment;
import io.julienmetral.tasks.task.exceptions.InvalidMentionException;
import io.julienmetral.tasks.task.exceptions.TaskCommentNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TooManyCommentAttachmentsException;
import io.julienmetral.tasks.task.repositories.TaskCommentRepository;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.julienmetral.tasks.support.UserSummaries.active;
import static io.julienmetral.tasks.support.UserSummaries.reference;
import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCommentServiceTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CAROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskCommentRepository commentRepository;

    @Mock
    private TaskAttachmentService attachmentService;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TaskCommentService service;

    private final Task task = new Task();
    private final UserSummary authorReference = reference(AUTHOR_ID);

    @BeforeEach
    void setUp() {
        task.setId(TASK_ID);
        task.setReference("TASK-1");
        task.setTitle("Write tests");
        task.setAssignedTo(active(ASSIGNEE_ID, "Alice"));
    }

    private static String mention(UUID id) {
        return "<@" + id + ">";
    }

    private static MultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", new byte[]{1});
    }

    private static List<MultipartFile> files(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> file("file-" + i + ".txt"))
                .toList();
    }

    private static UserSummary withStatus(UUID id, UserStatus status) {
        return switch (status) {
            case ACTIVE -> active(id, "User");
            case UNVERIFIED -> summary(id, "User", true, null, null);
            case DISABLED -> summary(id, "User", false, VERIFIED_AT, null);
            case DELETED -> summary(id, "User", true, VERIFIED_AT, Instant.parse("2026-02-01T00:00:00Z"));
        };
    }

    private void stubTask() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    }

    private void stubAuthor() {
        when(currentUser.getId()).thenReturn(Optional.of(AUTHOR_ID));
    }

    private void stubTaskAndAuthor() {
        stubTask();
        stubAuthor();
        when(userSummaryRepository.getReferenceById(AUTHOR_ID)).thenReturn(authorReference);
    }

    private void stubAddUpToSave() {
        stubTaskAndAuthor();
        when(commentRepository.save(any(TaskComment.class))).thenAnswer(invocation -> {
            TaskComment comment = invocation.getArgument(0);
            comment.setId(COMMENT_ID);
            return comment;
        });
    }

    private void stubUsers(Set<UUID> ids, UserSummary... users) {
        when(userSummaryRepository.findAllById(ids)).thenReturn(List.of(users));
    }

    private List<Object> publishedEvents(int expected) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    private TaskComment existingComment(String body, UserSummary... mentions) {
        TaskComment comment = new TaskComment();
        comment.setId(COMMENT_ID);
        comment.setTask(task);
        comment.setAuthor(authorReference);
        comment.setBody(body);
        comment.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        comment.getMentions().addAll(List.of(mentions));
        return comment;
    }

    private void stubComment(TaskComment comment) {
        stubTask();
        when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.of(comment));
    }

    @Nested
    class Add {

        @Test
        void moreThanFiveFilesAreRejectedBeforeAnyLookup() {
            assertThatThrownBy(() -> service.add(TASK_ID, "Too many", files(TaskCommentService.MAX_FILES + 1)))
                    .isInstanceOf(TooManyCommentAttachmentsException.class)
                    .hasMessage("A comment can have at most 5 files");
            verifyNoInteractions(taskRepository, commentRepository, attachmentService, taskEventService,
                    userSummaryRepository, currentUser, eventPublisher);
        }

        @Test
        void exactlyFiveFilesAreAccepted() {
            stubAddUpToSave();

            TaskComment result = service.add(TASK_ID, "Five files", files(TaskCommentService.MAX_FILES));

            verify(attachmentService, times(5)).add(any(Task.class), any(TaskComment.class), any(MultipartFile.class));
            assertThat(result.getAttachments()).hasSize(5);
        }

        @Test
        void unknownTaskIsRejectedWithoutSavingAnything() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(TASK_ID, "Hello", List.of()))
                    .isInstanceOf(TaskNotFoundException.class)
                    .hasMessageContaining(TASK_ID.toString());
            verifyNoInteractions(commentRepository, attachmentService, taskEventService, eventPublisher);
        }

        @Test
        void savesTheCommentByTheCurrentUserWithoutMentions() {
            stubAddUpToSave();
            Instant before = Instant.now();

            TaskComment result = service.add(TASK_ID, "Looks good", List.of());

            ArgumentCaptor<TaskComment> captor = ArgumentCaptor.forClass(TaskComment.class);
            verify(commentRepository).save(captor.capture());
            TaskComment saved = captor.getValue();
            assertThat(result).isSameAs(saved);
            assertThat(saved.getTask()).isSameAs(task);
            assertThat(saved.getAuthor()).isSameAs(authorReference);
            assertThat(saved.getBody()).isEqualTo("Looks good");
            assertThat(saved.getMentions()).isEmpty();
            assertThat(saved.getAttachments()).isEmpty();
            assertThat(saved.getCreatedAt()).isBetween(before, Instant.now());
            assertThat(saved.getEditedAt()).isNull();
            verify(userSummaryRepository, never()).findAllById(any());
            verifyNoInteractions(attachmentService);
        }

        @Test
        void recordsTheCommentAddedEvent() {
            stubAddUpToSave();

            TaskComment result = service.add(TASK_ID, "Looks good", List.of());

            verify(taskEventService).commentAdded(task, result);
        }

        @Test
        void withoutMentionsPublishesOnlyTaskCommentAdded() {
            stubAddUpToSave();

            service.add(TASK_ID, "Looks good", List.of());

            assertThat(publishedEvents(1)).containsExactly(new TaskCommentAdded(
                    TASK_ID, "TASK-1", "Write tests", COMMENT_ID, "Looks good",
                    ASSIGNEE_ID, AUTHOR_ID, Set.of()
            ));
        }

        @Test
        void onAnUnassignedTaskPublishesANullAssignee() {
            task.setAssignedTo(null);
            stubAddUpToSave();

            service.add(TASK_ID, "Anyone?", List.of());

            TaskCommentAdded event = (TaskCommentAdded) publishedEvents(1).getFirst();
            assertThat(event.assigneeId()).isNull();
        }

        @Test
        void storesTheMentionedUsers() {
            UserSummary bob = active(BOB_ID, "Bob");
            UserSummary carol = active(CAROL_ID, "Carol");
            stubAddUpToSave();
            stubUsers(Set.of(BOB_ID, CAROL_ID), bob, carol);

            TaskComment result = service.add(TASK_ID, mention(BOB_ID) + " and " + mention(CAROL_ID), List.of());

            assertThat(result.getMentions()).containsExactlyInAnyOrder(bob, carol);
        }

        @Test
        void withMentionsPublishesTaskCommentAddedThenUsersMentionedInComment() {
            String body = "%s please ask %s".formatted(mention(CAROL_ID), mention(BOB_ID));
            stubAddUpToSave();
            stubUsers(Set.of(BOB_ID, CAROL_ID), active(BOB_ID, "Bob"), active(CAROL_ID, "Carol"));

            service.add(TASK_ID, body, List.of());

            assertThat(publishedEvents(2)).containsExactly(
                    new TaskCommentAdded(
                            TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body,
                            ASSIGNEE_ID, AUTHOR_ID, Set.of(BOB_ID, CAROL_ID)
                    ),
                    new UsersMentionedInComment(
                            TASK_ID, "TASK-1", "Write tests", COMMENT_ID, body,
                            AUTHOR_ID, Set.of(BOB_ID, CAROL_ID)
                    )
            );
        }

        @Test
        void aRepeatedMentionIsLookedUpAndPublishedOnce() {
            String body = mention(BOB_ID) + " " + mention(BOB_ID);
            stubAddUpToSave();
            stubUsers(Set.of(BOB_ID), active(BOB_ID, "Bob"));

            TaskComment result = service.add(TASK_ID, body, List.of());

            assertThat(result.getMentions()).hasSize(1);
            UsersMentionedInComment mentioned = (UsersMentionedInComment) publishedEvents(2).get(1);
            assertThat(mentioned.mentionedUserIds()).containsExactly(BOB_ID);
        }

        @Test
        void mentionOfAnUnknownUserIsRejectedBeforeSaving() {
            stubTaskAndAuthor();
            stubUsers(Set.of(BOB_ID));

            assertThatThrownBy(() -> service.add(TASK_ID, mention(BOB_ID), List.of(file("a.txt"))))
                    .isInstanceOf(InvalidMentionException.class)
                    .hasMessage("User " + BOB_ID + " cannot be mentioned: no such user");
            verify(commentRepository, never()).save(any());
            verifyNoInteractions(attachmentService, taskEventService, eventPublisher);
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = {"UNVERIFIED", "DISABLED", "DELETED"})
        void mentionOfANonActiveUserIsRejectedBeforeSaving(UserStatus status) {
            stubTaskAndAuthor();
            stubUsers(Set.of(BOB_ID), withStatus(BOB_ID, status));

            assertThatThrownBy(() -> service.add(TASK_ID, "cc " + mention(BOB_ID), List.of()))
                    .isInstanceOf(InvalidMentionException.class)
                    .hasMessage("User " + BOB_ID + " cannot be mentioned: account is " + status);
            verify(commentRepository, never()).save(any());
            verifyNoInteractions(attachmentService, taskEventService, eventPublisher);
        }

        @Test
        void oneInactiveUserAmongActiveOnesRejectsTheWholeComment() {
            stubTaskAndAuthor();
            stubUsers(Set.of(BOB_ID, CAROL_ID), active(BOB_ID, "Bob"), withStatus(CAROL_ID, UserStatus.DISABLED));

            assertThatThrownBy(() -> service.add(TASK_ID, mention(BOB_ID) + mention(CAROL_ID), List.of()))
                    .isInstanceOf(InvalidMentionException.class)
                    .hasMessageContaining(CAROL_ID.toString());
            verify(commentRepository, never()).save(any());
        }

        @Test
        void authorsCanMentionThemselves() {
            UserSummary self = active(AUTHOR_ID, "Ada");
            stubAddUpToSave();
            stubUsers(Set.of(AUTHOR_ID), self);

            TaskComment result = service.add(TASK_ID, "note to " + mention(AUTHOR_ID), List.of());

            assertThat(result.getMentions()).containsExactly(self);
        }

        @Test
        void storesEachFileAsAnAttachmentLinkedToTheSavedComment() {
            MultipartFile first = file("first.txt");
            MultipartFile second = file("second.txt");
            TaskAttachment firstAttachment = new TaskAttachment();
            TaskAttachment secondAttachment = new TaskAttachment();
            stubAddUpToSave();
            when(attachmentService.add(any(Task.class), any(TaskComment.class), any(MultipartFile.class)))
                    .thenAnswer(invocation -> invocation.getArgument(2) == first ? firstAttachment : secondAttachment);

            TaskComment result = service.add(TASK_ID, "See files", List.of(first, second));

            assertThat(result.getAttachments()).containsExactly(firstAttachment, secondAttachment);
            InOrder order = inOrder(commentRepository, taskEventService, attachmentService, eventPublisher);
            order.verify(commentRepository).save(result);
            order.verify(taskEventService).commentAdded(task, result);
            order.verify(attachmentService).add(task, result, first);
            order.verify(attachmentService).add(task, result, second);
            order.verify(eventPublisher).publishEvent(any(TaskCommentAdded.class));
        }

        @Test
        void nothingIsPublishedWhenAFileCannotBeStored() {
            StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
            stubAddUpToSave();
            stubUsers(Set.of(BOB_ID), active(BOB_ID, "Bob"));
            when(attachmentService.add(any(Task.class), any(TaskComment.class), any(MultipartFile.class)))
                    .thenThrow(failure);

            assertThatThrownBy(() -> service.add(TASK_ID, mention(BOB_ID), List.of(file("a.txt"))))
                    .isSameAs(failure);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void withoutAuthenticatedUserIsRejected() {
            stubTask();
            when(currentUser.getId()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(TASK_ID, "Hello", List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No authenticated user");
            verifyNoInteractions(commentRepository, eventPublisher);
        }
    }

    @Nested
    class Edit {

        @Test
        void sameBodyIsANoOp() {
            TaskComment comment = existingComment("Unchanged " + mention(BOB_ID), active(BOB_ID, "Bob"));
            stubComment(comment);

            TaskComment result = service.edit(TASK_ID, COMMENT_ID, "Unchanged " + mention(BOB_ID));

            assertThat(result).isSameAs(comment);
            assertThat(comment.getEditedAt()).isNull();
            verifyNoInteractions(taskEventService, eventPublisher, userSummaryRepository);
        }

        @Test
        void replacesTheBodyAndSetsEditedAt() {
            TaskComment comment = existingComment("Before");
            stubComment(comment);
            Instant before = Instant.now();

            TaskComment result = service.edit(TASK_ID, COMMENT_ID, "After");

            assertThat(result).isSameAs(comment);
            assertThat(comment.getBody()).isEqualTo("After");
            assertThat(comment.getEditedAt()).isBetween(before, Instant.now());
            assertThat(comment.getCreatedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        }

        @Test
        void recordsTheCommentEditedEvent() {
            TaskComment comment = existingComment("Before");
            stubComment(comment);

            service.edit(TASK_ID, COMMENT_ID, "After");

            verify(taskEventService).commentEdited(task, comment);
            verify(taskEventService, never()).commentAdded(any(), any());
        }

        @Test
        void withoutNewMentionsPublishesNothing() {
            stubComment(existingComment("Before"));

            service.edit(TASK_ID, COMMENT_ID, "After");

            verifyNoInteractions(eventPublisher, userSummaryRepository);
        }

        @Test
        void keepsMentionsOfUsersWhoBecameInactive() {
            UserSummary disabledBob = withStatus(BOB_ID, UserStatus.DISABLED);
            TaskComment comment = existingComment("Hi " + mention(BOB_ID), disabledBob);
            stubComment(comment);

            service.edit(TASK_ID, COMMENT_ID, "Hello again " + mention(BOB_ID));

            assertThat(comment.getMentions()).containsExactly(disabledBob);
            assertThat(comment.getBody()).isEqualTo("Hello again " + mention(BOB_ID));
            verifyNoInteractions(userSummaryRepository, eventPublisher);
        }

        @Test
        void keepsMentionsOfUsersWhoWereDeleted() {
            UserSummary deletedBob = withStatus(BOB_ID, UserStatus.DELETED);
            TaskComment comment = existingComment("Hi " + mention(BOB_ID), deletedBob);
            stubComment(comment);

            service.edit(TASK_ID, COMMENT_ID, mention(BOB_ID) + " fixed a typo");

            assertThat(comment.getMentions()).containsExactly(deletedBob);
            verifyNoInteractions(userSummaryRepository, eventPublisher);
        }

        @Test
        void dropsMentionsRemovedFromTheBody() {
            UserSummary bob = active(BOB_ID, "Bob");
            UserSummary carol = active(CAROL_ID, "Carol");
            TaskComment comment = existingComment(mention(BOB_ID) + " " + mention(CAROL_ID), bob, carol);
            stubComment(comment);

            service.edit(TASK_ID, COMMENT_ID, "Only " + mention(BOB_ID));

            assertThat(comment.getMentions()).containsExactly(bob);
            verifyNoInteractions(userSummaryRepository, eventPublisher);
        }

        @Test
        void removingEveryMentionLeavesNone() {
            TaskComment comment = existingComment(mention(BOB_ID), active(BOB_ID, "Bob"));
            stubComment(comment);

            service.edit(TASK_ID, COMMENT_ID, "Nobody");

            assertThat(comment.getMentions()).isEmpty();
        }

        @Test
        void validatesAndPublishesOnlyTheNewMentions() {
            UserSummary bob = active(BOB_ID, "Bob");
            UserSummary carol = active(CAROL_ID, "Carol");
            TaskComment comment = existingComment("Hi " + mention(BOB_ID), bob);
            String newBody = "Hi " + mention(BOB_ID) + " and " + mention(CAROL_ID);
            stubComment(comment);
            stubAuthor();
            stubUsers(Set.of(CAROL_ID), carol);

            service.edit(TASK_ID, COMMENT_ID, newBody);

            verify(userSummaryRepository).findAllById(Set.of(CAROL_ID));
            assertThat(comment.getMentions()).containsExactlyInAnyOrder(bob, carol);
            assertThat(publishedEvents(1)).containsExactly(new UsersMentionedInComment(
                    TASK_ID, "TASK-1", "Write tests", COMMENT_ID, newBody, AUTHOR_ID, Set.of(CAROL_ID)
            ));
        }

        @Test
        void neverPublishesTaskCommentAdded() {
            stubComment(existingComment("Before"));
            stubAuthor();
            stubUsers(Set.of(BOB_ID), active(BOB_ID, "Bob"));

            service.edit(TASK_ID, COMMENT_ID, "After " + mention(BOB_ID));

            assertThat(publishedEvents(1)).noneMatch(TaskCommentAdded.class::isInstance);
        }

        @Test
        void mentioningAgainAUserRemovedByAnEarlierEditCountsAsNew() {
            TaskComment comment = existingComment("Nobody");
            stubComment(comment);
            stubAuthor();
            stubUsers(Set.of(BOB_ID), active(BOB_ID, "Bob"));

            service.edit(TASK_ID, COMMENT_ID, "Back to " + mention(BOB_ID));

            UsersMentionedInComment event = (UsersMentionedInComment) publishedEvents(1).getFirst();
            assertThat(event.mentionedUserIds()).containsExactly(BOB_ID);
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = {"UNVERIFIED", "DISABLED", "DELETED"})
        void newMentionOfANonActiveUserIsRejectedAndLeavesTheCommentUnchanged(UserStatus status) {
            UserSummary bob = active(BOB_ID, "Bob");
            TaskComment comment = existingComment("Hi " + mention(BOB_ID), bob);
            stubComment(comment);
            stubUsers(Set.of(CAROL_ID), withStatus(CAROL_ID, status));

            assertThatThrownBy(() -> service.edit(TASK_ID, COMMENT_ID, "Hi " + mention(CAROL_ID)))
                    .isInstanceOf(InvalidMentionException.class)
                    .hasMessage("User " + CAROL_ID + " cannot be mentioned: account is " + status);
            assertThat(comment.getBody()).isEqualTo("Hi " + mention(BOB_ID));
            assertThat(comment.getMentions()).containsExactly(bob);
            assertThat(comment.getEditedAt()).isNull();
            verifyNoInteractions(taskEventService, eventPublisher);
        }

        @Test
        void newMentionOfAnUnknownUserIsRejected() {
            stubComment(existingComment("Before"));
            stubUsers(Set.of(CAROL_ID));

            assertThatThrownBy(() -> service.edit(TASK_ID, COMMENT_ID, mention(CAROL_ID)))
                    .isInstanceOf(InvalidMentionException.class)
                    .hasMessage("User " + CAROL_ID + " cannot be mentioned: no such user");
            verifyNoInteractions(taskEventService, eventPublisher);
        }

        @Test
        void unknownTaskIsRejected() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.edit(TASK_ID, COMMENT_ID, "After"))
                    .isInstanceOf(TaskNotFoundException.class);
            verifyNoInteractions(commentRepository, taskEventService, eventPublisher);
        }

        @Test
        void commentOfAnotherTaskIsRejected() {
            stubTask();
            when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.edit(TASK_ID, COMMENT_ID, "After"))
                    .isInstanceOf(TaskCommentNotFoundException.class)
                    .hasMessage("Comment not found with id: " + COMMENT_ID);
            verifyNoInteractions(taskEventService, eventPublisher);
        }
    }

    @Nested
    class Delete {

        private TaskAttachment attachment() {
            TaskAttachment attachment = new TaskAttachment();
            attachment.setId(UUID.randomUUID());
            return attachment;
        }

        @Test
        void removesEachAttachmentBeforeRecordingAndDeletingTheComment() {
            TaskAttachment first = attachment();
            TaskAttachment second = attachment();
            TaskComment comment = existingComment("With files");
            comment.getAttachments().addAll(List.of(first, second));
            stubComment(comment);

            service.delete(TASK_ID, COMMENT_ID);

            InOrder order = inOrder(attachmentService, taskEventService, commentRepository);
            order.verify(attachmentService).remove(task, first);
            order.verify(attachmentService).remove(task, second);
            order.verify(taskEventService).commentDeleted(task, comment);
            order.verify(commentRepository).delete(comment);
        }

        @Test
        void removesEveryAttachmentEvenWhenRemovalDetachesItFromTheComment() {
            TaskAttachment first = attachment();
            TaskAttachment second = attachment();
            TaskComment comment = existingComment("With files");
            comment.getAttachments().addAll(List.of(first, second));
            stubComment(comment);
            doAnswer(invocation -> comment.getAttachments().remove(invocation.getArgument(1)))
                    .when(attachmentService).remove(any(Task.class), any(TaskAttachment.class));

            service.delete(TASK_ID, COMMENT_ID);

            verify(attachmentService).remove(task, first);
            verify(attachmentService).remove(task, second);
            verify(commentRepository).delete(comment);
        }

        @Test
        void commentWithoutFilesIsRecordedAndDeleted() {
            TaskComment comment = existingComment("No files");
            stubComment(comment);

            service.delete(TASK_ID, COMMENT_ID);

            verifyNoInteractions(attachmentService);
            InOrder order = inOrder(taskEventService, commentRepository);
            order.verify(taskEventService).commentDeleted(task, comment);
            order.verify(commentRepository).delete(comment);
        }

        @Test
        void publishesNoEvent() {
            stubComment(existingComment("Bye " + mention(BOB_ID), active(BOB_ID, "Bob")));

            service.delete(TASK_ID, COMMENT_ID);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        void unknownTaskIsRejectedWithoutDeletingAnything() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(TASK_ID, COMMENT_ID))
                    .isInstanceOf(TaskNotFoundException.class);
            verifyNoInteractions(commentRepository, attachmentService, taskEventService);
        }

        @Test
        void commentOfAnotherTaskIsRejectedWithoutDeletingAnything() {
            stubTask();
            when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(TASK_ID, COMMENT_ID))
                    .isInstanceOf(TaskCommentNotFoundException.class);
            verify(commentRepository, never()).delete(any());
            verifyNoInteractions(attachmentService, taskEventService);
        }
    }

    @Nested
    class Find {

        @Test
        void findAllReturnsTheRepositoryPage() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<TaskComment> page = new PageImpl<>(List.of(existingComment("One")));
            stubTask();
            when(commentRepository.findAllByTaskId(TASK_ID, pageable)).thenReturn(page);

            assertThat(service.findAll(TASK_ID, pageable)).isSameAs(page);
        }

        @Test
        void findAllForAnUnknownTaskIsRejected() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findAll(TASK_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(TaskNotFoundException.class)
                    .hasMessageContaining(TASK_ID.toString());
            verifyNoInteractions(commentRepository);
        }

        @Test
        void findReturnsTheCommentOfTheTask() {
            TaskComment comment = existingComment("One");
            stubComment(comment);

            assertThat(service.find(TASK_ID, COMMENT_ID)).isSameAs(comment);
        }

        @Test
        void findForAnUnknownTaskIsRejected() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.find(TASK_ID, COMMENT_ID))
                    .isInstanceOf(TaskNotFoundException.class);
            verifyNoInteractions(commentRepository);
        }

        @Test
        void findCommentOfAnotherTaskIsRejected() {
            stubTask();
            when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.find(TASK_ID, COMMENT_ID))
                    .isInstanceOf(TaskCommentNotFoundException.class)
                    .hasMessage("Comment not found with id: " + COMMENT_ID);
        }
    }
}
