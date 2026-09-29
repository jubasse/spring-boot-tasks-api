package io.julienmetral.tasks.export.personaldata;

import io.julienmetral.tasks.export.ExportProperties;
import mockwebserver3.MockWebServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalDataPdfTest {

    private static final int LIMIT = 3;

    @TempDir
    Path directory;

    private final PersonalDataPdf pdf = new PersonalDataPdf(properties(LIMIT));

    private static ExportProperties properties(int pdfMaxRowsPerSection) {
        return new ExportProperties(
                "https://app.example/exports",
                Duration.ofDays(7),
                Duration.ofDays(30),
                500,
                Duration.ofMinutes(15),
                3,
                false,
                "0 30 4 * * *",
                false,
                Duration.ofMinutes(5),
                pdfMaxRowsPerSection
        );
    }

    @Test
    void everySectionShowsItsValues() throws IOException {
        Map<String, Object> data = data();
        data.put("webhooks", List.of(row(
                "kind", "WEBHOOK", "url", "https://hooks.example.com/tasks",
                "events", "task.assigned task.due_soon", "created_at", "2030-01-02T10:00:00Z")));
        data.put("tasks", List.of(row(
                "reference", "PDF-1", "title", "Prepare the audit", "status", "DONE",
                "created_at", "2030-01-03T10:00:00Z", "deleted_at", "2030-01-04T10:00:00Z")));
        data.put("comments", List.of(row(
                "task_reference", "PDF-1", "body", "Looks good to me", "created_at", "2030-01-05T10:00:00Z")));
        data.put("attachments", List.of(row(
                "task_reference", "PDF-1", "original_filename", "invoice.pdf", "size_bytes", 2048,
                "created_at", "2030-01-06T10:00:00Z")));
        data.put("history", List.of(row(
                "task_reference", "PDF-1", "type", "STATUS_CHANGED", "occurred_at", "2030-01-07T10:00:00Z")));
        data.put("exports", List.of(row(
                "type", "PERSONAL_DATA", "status", "RUNNING", "created_at", "2030-01-08T10:00:00Z",
                "completed_at", null)));
        data.put("notification_settings", row(
                "task_assigned", true, "task_unassigned", false, "task_cancelled", true, "task_deleted", true,
                "task_commented", true, "task_mentioned", true, "task_due_soon", true, "task_overdue", true));

        String text = render(data);

        assertThat(text).contains(
                "Your data in Tasks",
                "jane@example.com", "Jane Doe", "ACTIVE", "ADMIN USER", "Yes, in the archive",
                "Task assigned On", "Task unassigned Off",
                "https://hooks.example.com/tasks",
                "PDF-1", "Prepare the audit", "DONE",
                "Looks good to me",
                "invoice.pdf", "2048",
                "STATUS_CHANGED",
                "PERSONAL_DATA RUNNING");
        // Narrow cells break dates and lists of events across lines
        assertThat(compact(text)).contains(
                "2030-01-10T08:00:00Z",
                "task.assignedtask.due_soon",
                "2030-01-03T10:00:00Z", "2030-01-04T10:00:00Z", "2030-01-05T10:00:00Z", "2030-01-06T10:00:00Z",
                "2030-01-07T10:00:00Z", "2030-01-08T10:00:00Z");
    }

    @Test
    void emptySectionsAndDefaultSettingsAreSaidSo() throws IOException {
        Map<String, Object> data = data();
        accountOf(data).put("has_profile_photo", false);

        String text = render(data);

        assertThat(text)
                .contains("Default settings: every notification is on.")
                .contains("Profile photo None")
                .contains("Nothing.");
        assertThat(text.split("None\\.", -1)).hasSize(5);
    }

    @Test
    void lettersBeyondWesternEuropeAreWrittenWithTheEmbeddedNotoSans() throws IOException {
        Map<String, Object> data = data();
        accountOf(data).put("display_name", "Zoé Łukasz");
        data.put("tasks", List.of(row("reference", "PDF-2", "title", "Tâche été ł ř Ωμέγα Привет")));
        Path output = directory.resolve("my-data.pdf");

        pdf.render(data, output);

        assertThat(normalized(textOf(output))).contains("Zoé Łukasz", "Tâche été ł ř Ωμέγα Привет");
        assertThat(fontsOf(output)).isNotEmpty().allSatisfy(font -> {
            assertThat(font.getName()).contains("NotoSans");
            assertThat(font.isEmbedded()).isTrue();
        });
    }

    @Test
    void markupInAValueIsWrittenAsText() throws IOException {
        Map<String, Object> data = data();
        data.put("tasks", List.of(row("reference", "PDF-3", "title", "<b>bold</b> & <i>italic</i>")));
        data.put("comments", List.of(row(
                "task_reference", "PDF-3",
                "body", "<script>alert('x')</script><table><tr><td>cell</td></tr></table>")));

        String text = render(data);

        assertThat(text).contains(
                "<b>bold</b> & <i>italic</i>",
                "<script>alert('x')</script><table><tr><td>cell</td></tr></table>");
    }

    @Test
    void imageStyleOrLinkInAValueFetchesNothing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
            Map<String, Object> data = data();
            accountOf(data).put("display_name", server.url("/avatar.png").toString());
            data.put("tasks", List.of(row(
                    "reference", "PDF-4", "title", "<img src=\"" + server.url("/pixel.png") + "\"/>")));
            data.put("comments", List.of(row(
                    "task_reference", "PDF-4",
                    "body", "<link rel=\"stylesheet\" href=\"" + server.url("/style.css") + "\"/>")));
            data.put("attachments", List.of(row(
                    "task_reference", "PDF-4",
                    "original_filename", "@import url(" + server.url("/font.css") + ");")));

            String text = render(data);

            assertThat(server.getRequestCount()).isZero();
            assertThat(text).contains("<img src=", "pixel.png", "<link rel=", "@import url(");
        }
    }

    @Test
    void sectionLongerThanTheLimitShowsItsFirstRowsThenHowManyMoreTheJsonHolds() throws IOException {
        Map<String, Object> data = data();
        data.put("tasks", rows(LIMIT + 2, n -> row("reference", "TASK-" + n, "title", "Title " + n)));
        data.put("comments", rows(LIMIT + 1, n -> row("task_reference", "TASK-1", "body", "Comment " + n)));
        data.put("attachments", rows(LIMIT + 3, n -> row(
                "task_reference", "TASK-1", "original_filename", "file-" + n + ".pdf")));
        data.put("history", rows(LIMIT + 4, n -> row("task_reference", "TASK-1", "type", "EVENT_" + n)));

        String text = render(data);

        assertThat(text)
                .contains("TASK-" + LIMIT, "Comment " + LIMIT, "file-" + LIMIT + ".pdf", "EVENT_" + LIMIT)
                .doesNotContain("TASK-" + (LIMIT + 1), "Comment " + (LIMIT + 1), "file-" + (LIMIT + 1) + ".pdf",
                        "EVENT_" + (LIMIT + 1))
                .contains(
                        "And 2 more in my-data.json.",
                        "And 1 more in my-data.json.",
                        "And 3 more in my-data.json.",
                        "And 4 more in my-data.json.");
    }

    @Test
    void sectionOfExactlyTheLimitShowsEveryRowAndNoMoreLine() throws IOException {
        Map<String, Object> data = data();
        data.put("tasks", rows(LIMIT, n -> row("reference", "TASK-" + n, "title", "Title " + n)));

        String text = render(data);

        assertThat(text).contains("TASK-1", "TASK-" + LIMIT).doesNotContain("more in my-data.json");
    }

    private String render(Map<String, Object> data) throws IOException {
        Path output = directory.resolve("my-data.pdf");

        pdf.render(data, output);

        return normalized(textOf(output));
    }

    /** What my-data.json holds for an account with nothing but itself; sections are replaced per test. */
    private static Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", "tasks-api-personal-data");
        data.put("version", 1);
        data.put("exported_at", "2030-01-10T08:00:00Z");
        data.put("account", row(
                "id", "0199a3c4-0000-7000-8000-000000000001", "email", "jane@example.com",
                "display_name", "Jane Doe", "status", "ACTIVE", "roles", "ADMIN USER",
                "email_verified_at", "2030-01-01T09:00:00Z", "last_login_at", null, "last_active_at", null,
                "inactivity_warned_at", null, "created_at", "2030-01-01T08:00:00Z", "has_profile_photo", true));
        data.put("notification_settings", null);
        data.put("webhooks", List.of());
        data.put("tasks", List.of());
        data.put("comments", List.of());
        data.put("attachments", List.of());
        data.put("history", List.of());
        data.put("exports", List.of());
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> accountOf(Map<String, Object> data) {
        return (Map<String, Object>) data.get("account");
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();

        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }

        return row;
    }

    private static List<Map<String, Object>> rows(int count, IntFunction<Map<String, Object>> row) {
        return IntStream.rangeClosed(1, count).mapToObj(row).toList();
    }

    private static String textOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    // Table cells wrap: the tests look for words, whatever the line breaks between them
    private static String normalized(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static List<PDFont> fontsOf(Path pdf) throws IOException {
        List<PDFont> fonts = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();

                for (COSName name : resources.getFontNames()) {
                    fonts.add(resources.getFont(name));
                }
            }
        }

        return fonts;
    }
}
