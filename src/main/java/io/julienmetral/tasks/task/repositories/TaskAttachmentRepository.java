package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.TaskAttachment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = {"media", "media.uploadedBy", "media.uploadedBy.avatar"})
    List<TaskAttachment> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = {"media", "media.uploadedBy", "media.uploadedBy.avatar"})
    Optional<TaskAttachment> findByIdAndTaskId(UUID id, UUID taskId);

    boolean existsByIdAndMediaUploadedById(UUID id, UUID uploadedById);
}
