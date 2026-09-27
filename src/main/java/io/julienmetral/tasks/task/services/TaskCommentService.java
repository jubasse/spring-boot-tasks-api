package io.julienmetral.tasks.task.services;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.identity.services.ProfilesForDisplay;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskCommentService {

    // spring.servlet.multipart.max-request-size also caps the total size of the files of one comment
    public static final int MAX_FILES = 5;

    private final TaskRepository taskRepository;
    private final TaskCommentRepository commentRepository;
    private final TaskAttachmentService attachmentService;
    private final TaskEventService taskEventService;
    private final UserProfileRepository userProfileRepository;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Posts a comment; each file becomes a task attachment linked to it. Publishes {@link TaskCommentAdded} and, when
     * the body mentions anyone, {@link UsersMentionedInComment}.
     *
     * @throws InvalidMentionException when a mentioned user does not exist or is not active
     */
    @Transactional
    public TaskComment add(UUID taskId, String body, List<MultipartFile> files) {
        if (files.size() > MAX_FILES) {
            throw new TooManyCommentAttachmentsException(MAX_FILES);
        }

        Task task = getTask(taskId);
        Set<UUID> mentionedIds = CommentMentions.parse(body);

        TaskComment comment = new TaskComment();

        comment.setTask(task);
        comment.setAuthor(userProfileRepository.getReferenceById(actorId()));
        comment.setBody(body);
        comment.getMentions().addAll(mentionableUsers(mentionedIds));
        comment.setCreatedAt(Instant.now());

        TaskComment saved = commentRepository.save(comment);

        taskEventService.commentAdded(task, saved);

        for (MultipartFile file : files) {
            saved.getAttachments().add(attachmentService.add(task, saved, file));
        }

        eventPublisher.publishEvent(new TaskCommentAdded(
                task.getId(), task.getReference(), task.getTitle(), saved.getId(), body,
                task.currentAssigneeId(), actorId(), mentionedIds
        ));

        publishMentions(task, saved, mentionedIds);

        return withDetailsLoaded(saved);
    }

    @Transactional(readOnly = true)
    public Page<TaskComment> findAll(UUID taskId, Pageable pageable) {
        getTask(taskId);

        Page<TaskComment> comments = commentRepository.findAllByTaskId(taskId, pageable);

        comments.forEach(TaskCommentService::withDetailsLoaded);

        return comments;
    }

    @Transactional(readOnly = true)
    public TaskComment find(UUID taskId, UUID commentId) {
        getTask(taskId);

        return withDetailsLoaded(getComment(taskId, commentId));
    }

    /**
     * Replaces the body. Users mentioned before keep their mention even if they are no longer active; only users
     * mentioned for the first time are validated and receive {@link UsersMentionedInComment}.
     */
    @Transactional
    public TaskComment edit(UUID taskId, UUID commentId, String body) {
        Task task = getTask(taskId);
        TaskComment comment = getComment(taskId, commentId);

        if (comment.getBody().equals(body)) {
            return withDetailsLoaded(comment);
        }

        Set<UUID> mentionedIds = CommentMentions.parse(body);
        Set<UserProfile> kept = comment.getMentions()
                .stream()
                .filter(user -> mentionedIds.contains(user.getId()))
                .collect(Collectors.toSet());
        Set<UUID> newIds = new LinkedHashSet<>(mentionedIds);

        kept.forEach(user -> newIds.remove(user.getId()));

        Set<UserProfile> added = mentionableUsers(newIds);

        comment.getMentions().retainAll(kept);
        comment.getMentions().addAll(added);
        comment.setBody(body);
        comment.setEditedAt(Instant.now());

        taskEventService.commentEdited(task, comment);

        publishMentions(task, comment, newIds);

        return withDetailsLoaded(comment);
    }

    /** Deletes the comment with its files; the stored objects go once the transaction commits. */
    @Transactional
    public void delete(UUID taskId, UUID commentId) {
        Task task = getTask(taskId);
        TaskComment comment = getComment(taskId, commentId);

        for (TaskAttachment attachment : List.copyOf(comment.getAttachments())) {
            attachmentService.remove(task, attachment);
        }

        taskEventService.commentDeleted(task, comment);

        commentRepository.delete(comment);
    }

    private Set<UserProfile> mentionableUsers(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }

        Map<UUID, UserProfile> users = userProfileRepository
                .findAllById(ids)
                .stream()
                .collect(Collectors.toMap(UserProfile::getId, Function.identity()));

        Set<UserProfile> mentionable = new HashSet<>();

        for (UUID id : ids) {
            UserProfile user = users.get(id);

            if (user == null) {
                throw InvalidMentionException.unknownUser(id);
            }

            UserStatus status = user.getStatus();

            if (status != UserStatus.ACTIVE) {
                throw InvalidMentionException.inactiveUser(id, status);
            }

            mentionable.add(user);
        }

        return mentionable;
    }

    private void publishMentions(Task task, TaskComment comment, Set<UUID> mentionedIds) {
        if (mentionedIds.isEmpty()) {
            return;
        }

        eventPublisher.publishEvent(new UsersMentionedInComment(
                task.getId(), task.getReference(), task.getTitle(), comment.getId(), comment.getBody(),
                actorId(), Set.copyOf(mentionedIds)
        ));
    }

    private UUID actorId() {
        return currentUser
                .getId()
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    private Task getTask(UUID taskId) {
        return taskRepository
                .findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private TaskComment getComment(UUID taskId, UUID commentId) {
        return commentRepository
                .findByIdAndTaskId(commentId, taskId)
                .orElseThrow(() -> new TaskCommentNotFoundException(commentId));
    }

    // The response shows the author, the mentioned users and the files. With default_batch_fetch_size, the first
    // comment's collections load those of the whole page in a few IN queries.
    private static TaskComment withDetailsLoaded(TaskComment comment) {
        ProfilesForDisplay.load(comment.getAuthor());
        comment.getMentions().forEach(ProfilesForDisplay::load);
        comment.getAttachments().forEach(TaskAttachmentService::withMediaLoaded);

        return comment;
    }
}
