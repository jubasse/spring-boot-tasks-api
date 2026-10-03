package io.julienmetral.tasks.export.dtos;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataExportResponseDtoTest {

    private static final Instant CREATED_AT = Instant.parse("2030-01-01T08:00:00Z");

    private static final Instant COMPLETED_AT = Instant.parse("2030-01-01T08:01:00Z");

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-08T08:01:00Z");

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    private final Media media = new Media();

    private final DataExport export = new DataExport();

    DataExportResponseDtoTest() {
        export.setId(UUID.randomUUID());
        export.setType(DataExportType.TASKS_CSV);
        export.setTaskFilters(new TaskExportFilters(TaskStatus.DONE, null, false));
        export.setCreatedAt(CREATED_AT);
    }

    @Test
    void completedExportCarriesItsRowsDatesAndADownloadLink() {
        export.setStatus(DataExportStatus.COMPLETED);
        export.setMedia(media);
        export.setRowCount(12L);
        export.setCompletedAt(COMPLETED_AT);
        export.setExpiresAt(EXPIRES_AT);
        when(mediaUrls.of(media)).thenReturn("https://storage.example/export/key?signature");

        DataExportResponseDto dto = new DataExportResponseDto(export, mediaUrls);

        assertThat(dto).isEqualTo(new DataExportResponseDto(
                export.getId(),
                DataExportType.TASKS_CSV,
                DataExportStatus.COMPLETED,
                new TaskExportFilters(TaskStatus.DONE, null, false),
                12L,
                CREATED_AT,
                COMPLETED_AT,
                EXPIRES_AT,
                "https://storage.example/export/key?signature"
        ));
    }

    @ParameterizedTest
    @EnumSource(value = DataExportStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void exportThatIsNotCompletedHasNoDownloadLink(DataExportStatus status) {
        export.setStatus(status);
        export.setMedia(media);

        assertThat(new DataExportResponseDto(export, mediaUrls).downloadUrl()).isNull();
        verifyNoInteractions(mediaUrls);
    }

    @Test
    void completedExportWhoseFileIsGoneHasNoDownloadLink() {
        export.setStatus(DataExportStatus.COMPLETED);

        assertThat(new DataExportResponseDto(export, mediaUrls).downloadUrl()).isNull();
        verifyNoInteractions(mediaUrls);
    }

    @Test
    void usersExportHasNoFilters() {
        export.setType(DataExportType.USERS_CSV);
        export.setTaskFilters(null);
        export.setStatus(DataExportStatus.QUEUED);

        DataExportResponseDto dto = new DataExportResponseDto(export, mediaUrls);

        assertThat(dto.type()).isEqualTo(DataExportType.USERS_CSV);
        assertThat(dto.filters()).isNull();
        assertThat(dto.rowCount()).isNull();
    }
}
