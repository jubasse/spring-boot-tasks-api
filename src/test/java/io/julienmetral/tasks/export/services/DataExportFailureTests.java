package io.julienmetral.tasks.export.services;

import io.julienmetral.tasks.export.AbstractDataExportTests;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.services.ObjectStorage;
import io.julienmetral.tasks.support.DeadLetterIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Runs that fail, with the object storage made to fail through the spy of this context. */
@DeadLetterIntegrationTest
class DataExportFailureTests extends AbstractDataExportTests {

    private static final long EMAIL_TIMEOUT_MILLIS = 10_000;

    @Autowired
    private ObjectStorage objectStorage;

    @Autowired
    private JavaMailSender mailSender;

    private void storageRefusesExports() {
        doThrow(new StorageUnavailableException(new RuntimeException("storage down")))
                .when(objectStorage).put(startsWith("export/"), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void exportWhoseFileCannotBeStoredFailsWithTheKindOfFailureAndLeavesNothingBehind() throws Exception {
        User owner = createUser(UserRole.USER);
        insertTask(TaskRow.assignedTo(owner));
        storageRefusesExports();

        UUID exportId = exportIdOf(requestTasksExport(owner, "{\"assigneeId\": \"%s\"}".formatted(owner.getId())));

        assertThat(awaitEnded(exportId)).isEqualTo("FAILED");
        assertThat(failureOf(exportId)).isEqualTo("StorageUnavailableException");
        assertThat(jdbcTemplate.queryForMap("SELECT media_id, lease_until FROM data_exports WHERE id = ?", exportId))
                .containsEntry("media_id", null)
                .containsEntry("lease_until", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media WHERE uploaded_by_id = ? AND usage = 'EXPORT'",
                Integer.class,
                owner.getId()
        )).isZero();
        assertThat(Path.of(System.getProperty("java.io.tmpdir"), "exports", exportId + ".csv")).doesNotExist();

        JsonNode export = exportJson(owner, exportId);
        assertThat(export.path("status").asString()).isEqualTo("FAILED");
        assertThat(export.path("downloadUrl").isNull()).isTrue();
        assertThat(export.path("rowCount").isNull()).isTrue();
    }

    @Test
    void ownerOfAFailedExportIsEmailedTheReferenceToQuote() throws Exception {
        User owner = createUser(UserRole.USER);
        storageRefusesExports();

        UUID exportId = exportIdOf(requestTasksExport(owner, "{\"assigneeId\": \"%s\"}".formatted(owner.getId())));
        awaitEnded(exportId);

        verify(mailSender, timeout(EMAIL_TIMEOUT_MILLIS)).send(argThat((SimpleMailMessage mail) ->
                Arrays.asList(mail.getTo()).contains(owner.getEmail())
                        && "Your export could not be produced".equals(mail.getSubject())
                        && mail.getText().contains("reference: " + exportId + ".")));
    }

    @Test
    void failedExportLetsItsOwnerAskAgain() throws Exception {
        User owner = createUser(UserRole.USER);
        String body = "{\"assigneeId\": \"%s\"}".formatted(owner.getId());
        storageRefusesExports();
        UUID failed = exportIdOf(requestTasksExport(owner, body));
        assertThat(awaitEnded(failed)).isEqualTo("FAILED");
        doCallRealMethod().when(objectStorage).put(anyString(), any(InputStream.class), anyLong(), anyString());

        UUID retried = exportIdOf(requestTasksExport(owner, body));

        awaitCompleted(retried);
        assertThat(statusOf(failed)).isEqualTo("FAILED");
    }
}
