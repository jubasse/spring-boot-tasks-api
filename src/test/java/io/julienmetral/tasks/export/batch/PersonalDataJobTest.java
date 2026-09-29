package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.exceptions.DataExportNotFoundException;
import io.julienmetral.tasks.export.personaldata.PersonalDataPdf;
import io.julienmetral.tasks.export.personaldata.PersonalDataQueries;
import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.media.services.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.julienmetral.tasks.support.UserProfiles.reference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalDataJobTest {

    private static final Instant NOW = Instant.parse("2030-03-04T23:30:00Z");

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final byte[] PHOTO = {(byte) 0x89, 'P', 'N', 'G'};

    private final UUID exportId = UUID.randomUUID();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private JobRepository jobRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private DataExportService exportService;

    @Mock
    private DataExportJobListener jobListener;

    @Mock
    private DataExportLeaseRenewal leaseRenewal;

    @Mock
    private PersonalDataQueries queries;

    @Mock
    private PersonalDataPdf pdf;

    @Mock
    private ObjectStorage objectStorage;

    private SimpleJob job;

    @BeforeEach
    void buildTheJob() {
        Job built = new PersonalDataJob(jobRepository, transactionManager, exportService, jobListener, leaseRenewal,
                queries, pdf, objectStorage, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC)).personalDataExport();
        job = (SimpleJob) built;
    }

    @AfterEach
    void deleteTheFilesOfTheRun() {
        ExportFiles.deleteAll(exportId);
    }

    private RepeatStatus runStep(String name) throws Exception {
        JobExecution execution = new JobExecution(
                1L,
                new JobInstance(1L, PersonalDataJob.JOB),
                new JobParametersBuilder().addString(PublishExport.EXPORT_ID, exportId.toString()).toJobParameters()
        );
        StepExecution step = new StepExecution(2L, name, execution);
        execution.addStepExecution(step);

        return ((TaskletStep) job.getStep(name)).getTasklet()
                .execute(new StepContribution(step), new ChunkContext(new StepContext(step)));
    }

    private void ownedByTheOwner() {
        DataExport export = new DataExport();
        export.setId(exportId);
        export.setOwner(reference(OWNER_ID));
        when(exportService.find(exportId)).thenReturn(Optional.of(export));
    }

    private void hasProfilePhoto(String contentType) {
        when(queries.profilePhoto(OWNER_ID))
                .thenReturn(Map.of("storage_key", "avatar/key", "content_type", contentType));
        when(objectStorage.open("avatar/key")).thenReturn(new ByteArrayInputStream(PHOTO));
    }

    private void jsonAndPdfWritten() throws IOException {
        Files.writeString(ExportFiles.of(exportId, "my-data.json"), "{\"format\":\"tasks-api-personal-data\"}");
        Files.writeString(ExportFiles.of(exportId, "my-data.pdf"), "%PDF-1.7 personal data");
    }

    @Test
    void jobWritesTheJsonThenThePdfThenPublishesAndRunsEveryStepAgainOnARestart() {
        assertThat(job.getName()).isEqualTo(PersonalDataJob.JOB);
        assertThat(job.getStepNames()).containsExactly("personalDataJson", "personalDataPdf", "personalDataPublish");
        assertThat(job.getStepNames()).allSatisfy(name ->
                assertThat(((TaskletStep) job.getStep(name)).isAllowStartIfComplete()).as(name).isTrue());
    }

    @Test
    void jsonStepWritesEverySectionOfTheOwnerAfterTheFormatItsVersionAndTheExportDate() throws Exception {
        ownedByTheOwner();
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", OWNER_ID);
        account.put("email", "jane@example.com");
        account.put("created_at", Instant.parse("2030-01-01T08:00:00Z"));
        when(queries.account(OWNER_ID)).thenReturn(account);
        when(queries.notificationSettings(OWNER_ID)).thenReturn(null);
        when(queries.tasks(OWNER_ID)).thenReturn(List.of(Map.of("reference", "T-1", "title", "Été")));
        when(queries.exports(OWNER_ID)).thenReturn(List.of(Map.of("type", "PERSONAL_DATA")));

        assertThat(runStep("personalDataJson")).isEqualTo(RepeatStatus.FINISHED);

        JsonNode json = jsonMapper.readTree(ExportFiles.of(exportId, "my-data.json").toFile());
        assertThat(json.propertyNames()).containsExactly(
                "format", "version", "exported_at", "account", "notification_settings", "webhooks", "tasks",
                "comments", "attachments", "history", "exports");
        assertThat(json.path("format").asString()).isEqualTo("tasks-api-personal-data");
        assertThat(json.path("version").asInt()).isEqualTo(1);
        assertThat(json.path("exported_at").asString()).isEqualTo("2030-03-04T23:30:00Z");
        assertThat(json.path("account").path("id").asString()).isEqualTo(OWNER_ID.toString());
        assertThat(json.path("account").path("created_at").asString()).isEqualTo("2030-01-01T08:00:00Z");
        assertThat(json.path("notification_settings").isNull()).isTrue();
        assertThat(json.path("webhooks").isEmpty()).isTrue();
        assertThat(json.path("tasks").path(0).path("title").asString()).isEqualTo("Été");
        assertThat(json.path("exports").path(0).path("type").asString()).isEqualTo("PERSONAL_DATA");
        verify(queries).history(OWNER_ID);
        verify(queries).comments(OWNER_ID);
        verify(queries).attachments(OWNER_ID);
    }

    @Test
    void jsonStepOfAnExportThatIsGoneFails() {
        assertThatThrownBy(() -> runStep("personalDataJson")).isInstanceOf(DataExportNotFoundException.class);

        verifyNoInteractions(queries);
    }

    @Test
    void pdfStepRendersTheJsonTheStepBeforeWrote() throws Exception {
        Files.writeString(ExportFiles.of(exportId, "my-data.json"), """
                {"format": "tasks-api-personal-data", "version": 1, "tasks": [{"reference": "T-1"}]}
                """);

        runStep("personalDataPdf");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(pdf).render(data.capture(), eq(ExportFiles.of(exportId, "my-data.pdf")));
        assertThat(data.getValue())
                .containsEntry("format", "tasks-api-personal-data")
                .containsEntry("version", 1)
                .containsEntry("tasks", List.of(Map.of("reference", "T-1")));
    }

    @Test
    void publishStepArchivesTheJsonThePdfAndTheProfilePhotoThenCompletesTheExportWithoutRowCount() throws Exception {
        ownedByTheOwner();
        jsonAndPdfWritten();
        hasProfilePhoto("image/png");

        runStep("personalDataPublish");

        Path archive = ExportFiles.of(exportId, "export.zip");
        verify(exportService).complete(eq(exportId), eq(archive), eq("my-data-2030-03-04.zip"),
                eq("application/zip"), isNull());
        assertThat(entriesOf(archive)).containsExactly(
                Map.entry("my-data.json", "{\"format\":\"tasks-api-personal-data\"}"),
                Map.entry("my-data.pdf", "%PDF-1.7 personal data"),
                Map.entry("profile-photo.png", new String(PHOTO, UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"image/jpeg, jpg", "image/png, png", "image/webp, webp"})
    void profilePhotoKeepsTheExtensionOfItsType(String contentType, String extension) throws Exception {
        ownedByTheOwner();
        jsonAndPdfWritten();
        hasProfilePhoto(contentType);

        runStep("personalDataPublish");

        assertThat(entriesOf(ExportFiles.of(exportId, "export.zip")).keySet())
                .containsExactly("my-data.json", "my-data.pdf", "profile-photo." + extension);
    }

    @Test
    void accountWithoutProfilePhotoGetsNoPhotoInItsArchive() throws Exception {
        ownedByTheOwner();
        jsonAndPdfWritten();
        when(queries.profilePhoto(OWNER_ID)).thenReturn(null);

        runStep("personalDataPublish");

        assertThat(entriesOf(ExportFiles.of(exportId, "export.zip")).keySet())
                .containsExactly("my-data.json", "my-data.pdf");
        verifyNoInteractions(objectStorage);
    }

    private static Map<String, String> entriesOf(Path archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();

        try (InputStream file = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(file)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), UTF_8));
            }
        }

        return entries;
    }
}
