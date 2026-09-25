package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.TaskEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TaskEventRepository
        extends JpaRepository<TaskEvent, UUID> {

    Page<TaskEvent> findAllByTaskIdOrderByOccurredAtDesc(
            UUID taskId,
            Pageable pageable
    );
}