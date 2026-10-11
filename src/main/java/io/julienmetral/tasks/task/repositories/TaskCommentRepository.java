package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.TaskComment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"author", "author.avatar"})
    Page<TaskComment> findAllByTaskId(UUID taskId, Pageable pageable);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"author", "author.avatar"})
    Optional<TaskComment> findByIdAndTaskId(UUID id, UUID taskId);

    boolean existsByIdAndAuthorId(UUID id, UUID authorId);
}
