package io.julienmetral.tasks.export.dtos;

import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportTasksDtoTest {

    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void filtersKeepTheStatusTheAssigneeAndTheArchivedFlag() {
        ExportTasksDto dto = new ExportTasksDto(TaskStatus.BLOCKED, ASSIGNEE_ID, true);

        assertThat(dto.filters()).isEqualTo(new TaskExportFilters(TaskStatus.BLOCKED, ASSIGNEE_ID, true));
    }

    @Test
    void missingArchivedFlagMeansTheTasksThatAreNotArchived() {
        assertThat(new ExportTasksDto(null, null, null).filters()).isEqualTo(new TaskExportFilters(null, null, false));
    }

    @Test
    void archivedFalseStaysFalse() {
        assertThat(new ExportTasksDto(TaskStatus.DONE, null, false).filters().archived()).isFalse();
    }
}
