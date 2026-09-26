package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.media.model.MediaUsage;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixtures for the task comment API: posting as JSON or multipart, editing, deleting, and reading the rows back. */
abstract class AbstractTaskCommentApiTests extends AbstractTaskAttachmentApiTests {

    protected static String commentsOf(UUID taskId) {
        return TASKS + "/" + taskId + "/comments";
    }

    protected static String comment(UUID taskId, UUID commentId) {
        return commentsOf(taskId) + "/" + commentId;
    }

    protected static String mention(User user) {
        return "<@" + user.getId() + ">";
    }

    protected ResultActions postComment(RequestPostProcessor caller, UUID taskId, String body) throws Exception {
        return mockMvc.perform(post(commentsOf(taskId))
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson(body)));
    }

    protected ResultActions postComment(
            RequestPostProcessor caller,
            UUID taskId,
            String body,
            MockMultipartFile... files
    ) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(commentsOf(taskId));

        for (MockMultipartFile file : files) {
            request.file(file);
        }

        if (body != null) {
            request.param("body", body);
        }

        return mockMvc.perform(request.with(caller));
    }

    /** Posts a comment as JSON and returns its id. */
    protected UUID addComment(RequestPostProcessor caller, UUID taskId, String body) throws Exception {
        return idOf(postComment(caller, taskId, body).andExpect(status().isCreated()));
    }

    /** Posts a comment with files and returns its id. */
    protected UUID addComment(RequestPostProcessor caller, UUID taskId, String body, MockMultipartFile... files)
            throws Exception {
        return idOf(postComment(caller, taskId, body, files).andExpect(status().isCreated()));
    }

    protected ResultActions listComments(RequestPostProcessor caller, UUID taskId, String query) throws Exception {
        return mockMvc.perform(get(commentsOf(taskId) + query).with(caller));
    }

    protected ResultActions findComment(RequestPostProcessor caller, UUID taskId, UUID commentId) throws Exception {
        return mockMvc.perform(get(comment(taskId, commentId)).with(caller));
    }

    protected ResultActions editComment(RequestPostProcessor caller, UUID taskId, UUID commentId, String body)
            throws Exception {
        return mockMvc.perform(patch(comment(taskId, commentId))
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson(body)));
    }

    protected ResultActions deleteComment(RequestPostProcessor caller, UUID taskId, UUID commentId) throws Exception {
        return mockMvc.perform(delete(comment(taskId, commentId)).with(caller));
    }

    protected ResultActions deleteTask(User admin, UUID taskId) throws Exception {
        return mockMvc.perform(delete(TASKS + "/" + taskId).with(asAdmin(admin)));
    }

    protected String bodyJson(String body) {
        return jsonMapper.writeValueAsString(Map.of("body", body));
    }

    protected static MockMultipartFile pdfFile(String filename) {
        return new MockMultipartFile("files", filename, "application/pdf", uniquePdf());
    }

    protected static MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("files", filename, "application/octet-stream", content);
    }

    protected UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(json(result.andReturn().getResponse().getContentAsString()).get("id").asString());
    }

    protected int commentCount(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from task_comments where task_id = ?",
                Integer.class,
                taskId
        );
    }

    protected boolean commentExists(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "select exists (select 1 from task_comments where id = ?)",
                Boolean.class,
                commentId
        );
    }

    protected String bodyOf(UUID commentId) {
        return jdbcTemplate.queryForObject("select body from task_comments where id = ?", String.class, commentId);
    }

    protected Set<UUID> mentionRowsOf(UUID commentId) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select user_id from task_comment_mentions where comment_id = ?",
                UUID.class,
                commentId
        ));
    }

    protected Set<UUID> attachmentIdsOfComment(UUID commentId) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select id from task_attachments where comment_id = ?",
                UUID.class,
                commentId
        ));
    }

    protected boolean mediaExistsWithKey(String storageKey) {
        return jdbcTemplate.queryForObject(
                "select exists (select 1 from media where storage_key = ?)",
                Boolean.class,
                storageKey
        );
    }

    protected String referenceOf(UUID taskId) {
        return jdbcTemplate.queryForObject("select reference from tasks where id = ?", String.class, taskId);
    }

    /** Every task attachment object currently in the bucket. */
    protected Set<String> storedAttachmentKeys() {
        return s3Client
                .listObjectsV2Paginator(request -> request
                        .bucket(storageProperties.bucket())
                        .prefix(MediaUsage.TASK_ATTACHMENT.storagePrefix() + "/"))
                .contents()
                .stream()
                .map(S3Object::key)
                .collect(Collectors.toSet());
    }
}
