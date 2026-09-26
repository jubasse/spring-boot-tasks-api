package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.config.StorageProperties;
import io.julienmetral.tasks.identity.entities.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixtures for the task attachment API: uploads through MockMvc, downloads of the presigned URL, direct reads of the
 * object storage, and generated files whose content is unique per test.
 */
abstract class AbstractTaskAttachmentApiTests extends AbstractUserStateTaskApiTests {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static final String ODT = "application/vnd.oasis.opendocument.text";

    static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired
    protected S3Client s3Client;

    @Autowired
    protected StorageProperties storageProperties;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    protected static String attachmentsOf(UUID taskId) {
        return TASKS + "/" + taskId + "/attachments";
    }

    protected static String attachment(UUID taskId, UUID attachmentId) {
        return attachmentsOf(taskId) + "/" + attachmentId;
    }

    protected ResultActions upload(RequestPostProcessor caller, UUID taskId, String filename, byte[] content)
            throws Exception {
        return upload(caller, taskId, filename, "application/octet-stream", content);
    }

    protected ResultActions upload(
            RequestPostProcessor caller,
            UUID taskId,
            String filename,
            String declaredContentType,
            byte[] content
    ) throws Exception {
        return mockMvc.perform(multipart(attachmentsOf(taskId))
                .file(new MockMultipartFile("file", filename, declaredContentType, content))
                .with(caller));
    }

    /** Uploads a unique PDF and returns the new attachment's id. */
    protected UUID uploadPdf(RequestPostProcessor caller, UUID taskId, String filename) throws Exception {
        String body = upload(caller, taskId, filename, "application/pdf", uniquePdf())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(json(body).get("id").asString());
    }

    protected ResultActions list(RequestPostProcessor caller, UUID taskId) throws Exception {
        return mockMvc.perform(get(attachmentsOf(taskId)).with(caller));
    }

    protected ResultActions find(RequestPostProcessor caller, UUID taskId, UUID attachmentId) throws Exception {
        return mockMvc.perform(get(attachment(taskId, attachmentId)).with(caller));
    }

    protected ResultActions remove(RequestPostProcessor caller, UUID taskId, UUID attachmentId) throws Exception {
        return mockMvc.perform(delete(attachment(taskId, attachmentId)).with(caller));
    }

    /** History of the task, newest first, read through the API. */
    protected JsonNode eventsThroughApi(User reader, UUID taskId) throws Exception {
        String body = getEvents(reader, taskId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json(body).get("content");
    }

    protected String storageKeyOf(UUID attachmentId) {
        return jdbcTemplate.queryForObject(
                "select m.storage_key from task_attachments a join media m on m.id = a.media_id where a.id = ?",
                String.class,
                attachmentId
        );
    }

    protected int attachmentCount(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from task_attachments where task_id = ?",
                Integer.class,
                taskId
        );
    }

    protected int mediaCountUploadedBy(User uploader) {
        return jdbcTemplate.queryForObject(
                "select count(*) from media where uploaded_by_id = ?",
                Integer.class,
                uploader.getId()
        );
    }

    protected byte[] objectBytes(String key) {
        return s3Client
                .getObjectAsBytes(request -> request.bucket(storageProperties.bucket()).key(key))
                .asByteArray();
    }

    protected void assertObjectMissing(String key) {
        assertThatThrownBy(() -> s3Client.headObject(request -> request.bucket(storageProperties.bucket()).key(key)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    protected HttpResponse<byte[]> download(String presignedUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(presignedUrl)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);

        return response;
    }

    protected static byte[] uniquePdf() {
        return """
                %%PDF-1.4
                %% %s
                1 0 obj << /Type /Catalog >> endobj
                trailer << /Root 1 0 R >>
                %%%%EOF
                """.formatted(UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    protected static byte[] uniqueText() {
        return ("Meeting notes " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    protected static byte[] uniqueDocx() {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>";

        return zipOf(
                new Entry("[Content_Types].xml", contentTypes, false),
                new Entry("_rels/.rels", "<Relationships/>", false),
                new Entry("word/document.xml", "<document>" + UUID.randomUUID() + "</document>", false)
        );
    }

    // ODF requires an uncompressed "mimetype" entry first: that is what identifies the document type
    protected static byte[] uniqueOdt() {
        return zipOf(
                new Entry("mimetype", ODT, true),
                new Entry("content.xml", "<office:document-content>" + UUID.randomUUID() + "</office:document-content>",
                        false)
        );
    }

    private record Entry(String name, String content, boolean stored) {
    }

    private static byte[] zipOf(Entry... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                byte[] bytes = entry.content().getBytes(StandardCharsets.UTF_8);
                ZipEntry zipEntry = new ZipEntry(entry.name());

                if (entry.stored()) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(bytes.length);
                    zipEntry.setCompressedSize(bytes.length);
                    zipEntry.setCrc(crc.getValue());
                }

                zip.putNextEntry(zipEntry);
                zip.write(bytes);
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return out.toByteArray();
    }
}
