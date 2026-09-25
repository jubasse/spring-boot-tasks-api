package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDtosTest {

    @Test
    void createTaskDtoTrimsTextAndDefaultsPriority() {
        var dto = new CreateTaskDto("  TASK-1  ", "  Title  ", "  Desc  ", null, null, null);

        assertThat(dto.reference()).isEqualTo("TASK-1");
        assertThat(dto.title()).isEqualTo("Title");
        assertThat(dto.description()).isEqualTo("Desc");
        assertThat(dto.priority()).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    void createTaskDtoTurnsBlankOrMissingTextIntoNull() {
        var dto = new CreateTaskDto("   ", null, "", TaskPriority.HIGH, null, null);

        assertThat(dto.reference()).isNull();
        assertThat(dto.title()).isNull();
        assertThat(dto.description()).isNull();
        assertThat(dto.priority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    void updateTaskDtoKeepsProvidedTextAndNullsBlankText() {
        var dto = new UpdateTaskDto("  New title  ", "   ", null, null);

        assertThat(dto.title()).isEqualTo("New title");
        assertThat(dto.description()).isNull();
    }
}
