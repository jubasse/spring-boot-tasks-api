package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskPriority;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    // Fetch the users and their photos in the same query instead of one query per task. Warning: every graph here is
    // LOAD, not the default FETCH, which turns every attribute it does not list into LAZY, EAGER mappings included:
    // the account's roles and the tasks of events and comments were no longer loaded.
    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = {
            "assignedTo",
            "assignedTo.avatar",
            "createdBy",
            "createdBy.avatar"
    })
    @NullMarked
    Page<Task> findAll(Specification<Task> specification, Pageable pageable);

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = {
            "assignedTo",
            "assignedTo.avatar",
            "createdBy",
            "createdBy.avatar"
    })
    @NullMarked
    Optional<Task> findById(UUID id);

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = {
            "assignedTo",
            "assignedTo.avatar",
            "createdBy",
            "createdBy.avatar"
    })
    @NullMarked
    List<Task> findAll();

    // Native on purpose: JPQL queries are filtered by @SoftDelete, but tasks_referenceUQ also covers deleted tasks
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM tasks WHERE reference = :reference)",
            nativeQuery = true
    )
    boolean existsByReferenceIncludingDeleted(@Param("reference") String reference);

    boolean existsByIdAndAssignedToId(
            UUID taskId,
            UUID assignedToId
    );

    Optional<Task> findByReference(String reference);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByPriority(TaskPriority priority);

    List<Task> findByAssignedToId(UUID assignedToId);

    List<Task> findByStatusAndAssignedToId(
        TaskStatus status,
        UUID assignedToId
    );

    List<Task> findByArchivedAtIsNull();

    List<Task> findByArchivedAtIsNotNull();

    List<Task> findByDueAtIsNotNullOrderByDueAtAsc();

    List<Task> findByTitleContainingIgnoreCase(String title);
}
