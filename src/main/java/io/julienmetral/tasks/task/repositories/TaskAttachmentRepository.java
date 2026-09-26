package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.TaskAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {

    List<TaskAttachment> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);

    Optional<TaskAttachment> findByIdAndTaskId(UUID id, UUID taskId);

    boolean existsByIdAndMediaUploadedById(UUID id, UUID uploadedById);
}
