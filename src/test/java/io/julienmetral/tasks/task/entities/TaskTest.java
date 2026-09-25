package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    private static final UUID ASSOCIATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COLUMN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @Test
    void currentAssigneeIdPrefersTheAssociation() {
        Task task = new Task();
        task.setAssignedTo(user(ASSOCIATION_ID));
        ReflectionTestUtils.setField(task, "assignedToId", COLUMN_ID);

        assertThat(task.currentAssigneeId()).isEqualTo(ASSOCIATION_ID);
    }

    @Test
    void setAssignedToKeepsTheWritableIdColumnInSync() {
        Task task = new Task();

        task.setAssignedTo(user(ASSOCIATION_ID));

        assertThat(task.getAssignedToId()).isEqualTo(ASSOCIATION_ID);
        assertThat(task.currentAssigneeId()).isEqualTo(ASSOCIATION_ID);

        task.setAssignedTo(null);

        assertThat(task.getAssignedTo()).isNull();
        assertThat(task.getAssignedToId()).isNull();
    }

    @Test
    void setCreatedByKeepsTheWritableIdColumnInSync() {
        Task task = new Task();

        task.setCreatedBy(user(ASSOCIATION_ID));

        assertThat(task.getCreatedById()).isEqualTo(ASSOCIATION_ID);

        task.setCreatedBy(null);

        assertThat(task.getCreatedBy()).isNull();
        assertThat(task.getCreatedById()).isNull();
    }

    @Test
    void currentAssigneeIdFallsBackToTheColumnWhenTheAssigneeIsSoftDeleted() {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "assignedToId", COLUMN_ID);

        assertThat(task.currentAssigneeId()).isEqualTo(COLUMN_ID);
    }

    @Test
    void currentAssigneeIdIsNullForAnUnassignedTask() {
        assertThat(new Task().currentAssigneeId()).isNull();
    }
}
