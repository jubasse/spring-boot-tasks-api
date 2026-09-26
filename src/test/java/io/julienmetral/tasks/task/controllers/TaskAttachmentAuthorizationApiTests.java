package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may upload, read and remove task attachments. */
class TaskAttachmentAuthorizationApiTests extends AbstractTaskAttachmentApiTests {

    @Test
    void adminWhoIsNotTheAssigneeCanUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User otherAdmin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, createUser(UserRole.USER));

        upload(asAdmin(otherAdmin), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isCreated());
    }

    @Test
    void assigneeCanUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        upload(asUser(assignee), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadedBy.id").value(assignee.getId().toString()));
    }

    @Test
    void userWhoIsNotTheAssigneeCannotUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User outsider = createUser(UserRole.USER);
        UUID taskId = createTask(admin, createUser(UserRole.USER));

        upload(asUser(outsider), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isZero();
        assertThat(mediaCountUploadedBy(outsider)).isZero();
    }

    @Test
    void userCannotUploadToUnassignedTask() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        UUID taskId = createTask(admin, null);

        upload(asUser(user), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isForbidden());
    }

    @Test
    void formerAssigneeCannotUploadAnymore() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User formerAssignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, formerAssignee);

        assign(admin, taskId, createUser(UserRole.USER).getId())
                .andExpect(status().isOk());

        upload(asUser(formerAssignee), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isForbidden());
    }

    @Test
    void anyActiveUserCanListAndReadAttachments() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User outsider = createUser(UserRole.USER);
        UUID taskId = createTask(admin, createUser(UserRole.USER));
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");

        list(asUser(outsider), taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        find(asUser(outsider), taskId, attachmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attachmentId.toString()));
    }

    @Test
    void uploaderCanRemoveTheirAttachment() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "report.pdf");

        remove(asUser(assignee), taskId, attachmentId)
                .andExpect(status().isNoContent());

        assertThat(attachmentCount(taskId)).isZero();
    }

    @Test
    void uploaderWhoIsNoLongerAssigneeCanStillRemoveTheirAttachment() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User formerAssignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, formerAssignee);
        UUID attachmentId = uploadPdf(asUser(formerAssignee), taskId, "report.pdf");

        assign(admin, taskId, createUser(UserRole.USER).getId())
                .andExpect(status().isOk());

        remove(asUser(formerAssignee), taskId, attachmentId)
                .andExpect(status().isNoContent());
    }

    @Test
    void adminCanRemoveAnAttachmentUploadedByTheAssignee() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User otherAdmin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "report.pdf");

        remove(asAdmin(otherAdmin), taskId, attachmentId)
                .andExpect(status().isNoContent());

        assertThat(attachmentCount(taskId)).isZero();
    }

    @Test
    void assigneeCannotRemoveAnAttachmentTheyDidNotUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");
        String storageKey = storageKeyOf(attachmentId);

        remove(asUser(assignee), taskId, attachmentId)
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isOne();
        assertThat(objectBytes(storageKey)).isNotEmpty();
    }

    @Test
    void userWhoIsNotTheUploaderCannotRemove() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        User outsider = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "report.pdf");

        remove(asUser(outsider), taskId, attachmentId)
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isOne();
    }

    @Test
    void userRemovingUnknownAttachmentIsForbidden() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        remove(asUser(assignee), taskId, UUID.randomUUID())
                .andExpect(status().isForbidden());
    }

    @Test
    void unverifiedUserCannotListAttachments() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User unverified = createUnverifiedUser(UserRole.USER);
        UUID taskId = createTask(admin, null);
        UUID attachmentId = uploadPdf(asAdmin(admin), taskId, "report.pdf");

        list(asUser(unverified), taskId)
                .andExpect(status().isForbidden());
        find(asUser(unverified), taskId, attachmentId)
                .andExpect(status().isForbidden());
    }

    @Test
    void assigneeWhoLostTheirVerificationCannotUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);

        unverify(assignee);

        upload(asUser(assignee), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isZero();
    }

    @Test
    void unverifiedAdminCannotUpload() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User unverifiedAdmin = createUnverifiedUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        upload(asAdmin(unverifiedAdmin), taskId, "report.pdf", uniquePdf())
                .andExpect(status().isForbidden());
    }

    @Test
    void uploaderWhoLostTheirVerificationCannotRemove() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "report.pdf");

        unverify(assignee);

        remove(asUser(assignee), taskId, attachmentId)
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isOne();
    }

    @Test
    void disabledUploaderCannotRemove() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        UUID attachmentId = uploadPdf(asUser(assignee), taskId, "report.pdf");

        disableThroughApi(assignee);

        remove(asUser(assignee), taskId, attachmentId)
                .andExpect(status().isForbidden());

        assertThat(attachmentCount(taskId)).isOne();
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID taskId = createTask(admin, null);

        list(request -> request, taskId)
                .andExpect(status().isUnauthorized());
        upload(request -> request, taskId, "report.pdf", uniquePdf())
                .andExpect(status().isUnauthorized());
    }
}
