package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.exceptions.DataExportNotFoundException;
import io.julienmetral.tasks.export.personaldata.PersonalDataPdf;
import io.julienmetral.tasks.export.personaldata.PersonalDataQueries;
import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.media.services.ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The personal data export (GDPR articles 15 and 20): three tasklet steps, each reading what the step before wrote.
 * The first writes {@code my-data.json}, complete and meant for software; the second renders it to
 * {@code my-data.pdf}, for a person; the third zips both with the profile photo, and stores the archive.
 */
@Configuration
@RequiredArgsConstructor
public class PersonalDataJob {

    public static final String JOB = "personalDataExport";

    // Raised when the JSON changes shape, so that software reading older exports can tell them apart
    static final int FORMAT_VERSION = 1;

    private static final String JSON_FILE = "my-data.json";
    private static final String PDF_FILE = "my-data.pdf";
    private static final String ZIP_FILE = "export.zip";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataExportService exportService;
    private final DataExportJobListener jobListener;
    private final DataExportLeaseRenewal leaseRenewal;
    private final PersonalDataQueries queries;
    private final PersonalDataPdf pdf;
    private final ObjectStorage objectStorage;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Bean(JOB)
    Job personalDataExport() {
        return new JobBuilder(JOB, jobRepository)
                .listener(jobListener)
                .start(step("personalDataJson", this::writeJson))
                .next(step("personalDataPdf", this::writePdf))
                .next(step("personalDataPublish", this::publish))
                .build();
    }

    // Every step runs again on a restart: the files of an interrupted run stayed on the instance that stopped
    private Step step(String name, Tasklet tasklet) {
        return new StepBuilder(name, jobRepository)
                .allowStartIfComplete(true)
                .listener(leaseRenewal)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    private RepeatStatus writeJson(StepContribution contribution, ChunkContext context)
            throws IOException {
        UUID exportId = exportId(context);
        UUID ownerId = ownerOf(exportId);
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("format", "tasks-api-personal-data");
        data.put("version", FORMAT_VERSION);
        data.put("exported_at", clock.instant());
        data.put("account", queries.account(ownerId));
        data.put("notification_settings", queries.notificationSettings(ownerId));
        data.put("webhooks", queries.webhooks(ownerId));
        data.put("tasks", queries.tasks(ownerId));
        data.put("comments", queries.comments(ownerId));
        data.put("attachments", queries.attachments(ownerId));
        data.put("history", queries.history(ownerId));
        data.put("exports", queries.exports(ownerId));

        try (OutputStream out = Files.newOutputStream(ExportFiles.of(exportId, JSON_FILE))) {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(out, data);
        }

        return RepeatStatus.FINISHED;
    }

    private RepeatStatus writePdf(StepContribution contribution, ChunkContext context)
            throws IOException {
        UUID exportId = exportId(context);
        Map<String, Object> data;

        try (InputStream in = Files.newInputStream(ExportFiles.of(exportId, JSON_FILE))) {
            data = jsonMapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        }

        pdf.render(data, ExportFiles.of(exportId, PDF_FILE));

        return RepeatStatus.FINISHED;
    }

    private RepeatStatus publish(StepContribution contribution, ChunkContext context)
            throws IOException {
        UUID exportId = exportId(context);
        Path archive = ExportFiles.of(exportId, ZIP_FILE);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip, JSON_FILE, ExportFiles.of(exportId, JSON_FILE));
            add(zip, PDF_FILE, ExportFiles.of(exportId, PDF_FILE));
            addProfilePhoto(zip, ownerOf(exportId));
        }

        exportService.complete(
                exportId, archive, "my-data-" + LocalDate.now(clock) + ".zip", "application/zip", null);

        return RepeatStatus.FINISHED;
    }

    private void addProfilePhoto(ZipOutputStream zip, UUID ownerId) throws IOException {
        Map<String, Object> photo = queries.profilePhoto(ownerId);

        if (photo == null) {
            return;
        }

        String extension = switch (String.valueOf(photo.get("content_type"))) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };

        zip.putNextEntry(new ZipEntry("profile-photo." + extension));

        try (InputStream content = objectStorage.open((String) photo.get("storage_key"))) {
            content.transferTo(zip);
        }

        zip.closeEntry();
    }

    private static void add(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private UUID ownerOf(UUID exportId) {
        return exportService.find(exportId)
                .map(DataExport::getOwner)
                .map(UserProfile::getId)
                .orElseThrow(() -> new DataExportNotFoundException(exportId));
    }

    private static UUID exportId(ChunkContext context) {
        return UUID.fromString(context.getStepContext().getStepExecution().getJobExecution().getJobParameters()
                .getString(PublishExport.EXPORT_ID));
    }
}
