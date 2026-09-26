package io.julienmetral.tasks.task.entities;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.reference;
import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void currentAssigneeIdIsTheAssigneesId() {
        Task task = new Task();
        task.setAssignedTo(reference(ASSIGNEE_ID));

        assertThat(task.currentAssigneeId()).isEqualTo(ASSIGNEE_ID);
    }

    @Test
    void currentAssigneeIdIsNullForAnUnassignedTask() {
        assertThat(new Task().currentAssigneeId()).isNull();
    }
}
