package io.julienmetral.tasks.media.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUsageTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
    void avatarAllowsCommonPhotoFormats(String contentType) {
        assertThat(MediaUsage.AVATAR.allows(contentType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/svg+xml", "application/pdf", "application/zip", "text/html"})
    void avatarRejectsEverythingElse(String contentType) {
        assertThat(MediaUsage.AVATAR.allows(contentType)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
    void avatarUploadAllowsTheSamePhotoFormatsAsAvatar(String contentType) {
        assertThat(MediaUsage.AVATAR_UPLOAD.allows(contentType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/svg+xml", "application/pdf", "application/zip", "text/html"})
    void avatarUploadRejectsEverythingElse(String contentType) {
        assertThat(MediaUsage.AVATAR_UPLOAD.allows(contentType)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/pdf",
            "text/plain",
            "text/csv",
            "text/markdown",
            "application/rtf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation",
            "application/zip"
    })
    void taskAttachmentAllowsImagesDocumentsAndArchives(String contentType) {
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(contentType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "text/html",
            "image/svg+xml",
            "application/javascript",
            "application/x-msdownload",
            "application/x-sh",
            "application/octet-stream",
            "application/x-tika-msoffice",
            "IMAGE/PNG",
            ""
    })
    void taskAttachmentRejectsActiveContentExecutablesAndUnknownTypes(String contentType) {
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(contentType)).isFalse();
    }

    @Test
    void storagePrefixIsTheKebabCaseName() {
        assertThat(MediaUsage.AVATAR.storagePrefix()).isEqualTo("avatar");
        assertThat(MediaUsage.AVATAR_UPLOAD.storagePrefix()).isEqualTo("avatar-upload");
        assertThat(MediaUsage.TASK_ATTACHMENT.storagePrefix()).isEqualTo("task-attachment");
    }
}
