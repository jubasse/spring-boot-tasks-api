package io.julienmetral.tasks.task.repositories;

import io.julienmetral.tasks.support.RepositoryTest;
import io.julienmetral.tasks.task.entities.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class TaskRepositoryTests {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void existsByReferenceIncludingDeletedSeesASoftDeletedTaskThatDerivedQueriesIgnore() {
        String reference = uniqueReference();
        softDelete(entityManager.persistAndFlush(newTask(reference)));

        assertThat(taskRepository.findByReference(reference)).isEmpty();
        assertThat(taskRepository.existsByReferenceIncludingDeleted(reference)).isTrue();
        assertThat(taskRepository.existsByReferenceIncludingDeleted(uniqueReference())).isFalse();
    }

    @Test
    void referenceOfASoftDeletedTaskStaysTaken() {
        String reference = uniqueReference();
        softDelete(entityManager.persistAndFlush(newTask(reference)));

        assertThatThrownBy(() -> taskRepository.saveAndFlush(newTask(reference)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void softDelete(Task task) {
        taskRepository.delete(task);
        entityManager.flush();
        entityManager.clear();
    }

    private static Task newTask(String reference) {
        Task task = new Task();
        task.setReference(reference);
        task.setTitle("Repository task");
        return task;
    }

    private static String uniqueReference() {
        return "REPO-" + UUID.randomUUID().toString().substring(0, 18);
    }
}
