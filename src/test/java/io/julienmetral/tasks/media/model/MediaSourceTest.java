package io.julienmetral.tasks.media.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaSourceTest {

    private static final byte[] CONTENT = "image-bytes".getBytes(UTF_8);

    @Test
    void multipartSourceExposesTheNameSizeAndContentSentByTheClient() throws IOException {
        MediaSource source = MediaSource.of(
                new MockMultipartFile("file", "C:\\photos\\me.png", "image/png", CONTENT));

        assertThat(source.filename()).isEqualTo("C:\\photos\\me.png");
        assertThat(source.size()).isEqualTo(CONTENT.length);
        try (InputStream content = source.open()) {
            assertThat(content).hasBinaryContent(CONTENT);
        }
    }

    @Test
    void multipartSourceCanBeReadSeveralTimes() throws IOException {
        MediaSource source = MediaSource.of(new MockMultipartFile("file", "me.png", "image/png", CONTENT));

        try (InputStream first = source.open(); InputStream second = source.open()) {
            assertThat(first).hasBinaryContent(CONTENT);
            assertThat(second).hasBinaryContent(CONTENT);
        }
    }

    @Test
    void multipartSourcePropagatesAReadFailure() throws IOException {
        IOException failure = new IOException("gone");
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenThrow(failure);

        assertThatThrownBy(() -> MediaSource.of(file).open()).isSameAs(failure);
    }

    @Test
    void byteArraySourceExposesItsNameSizeAndContent() throws IOException {
        MediaSource source = MediaSource.of(CONTENT, "avatar.jpg");

        assertThat(source.filename()).isEqualTo("avatar.jpg");
        assertThat(source.size()).isEqualTo(CONTENT.length);
        try (InputStream content = source.open()) {
            assertThat(content).hasBinaryContent(CONTENT);
        }
    }

    @Test
    void byteArraySourceGivesAFreshStreamOnEveryOpen() throws IOException {
        MediaSource source = MediaSource.of(CONTENT, "avatar.jpg");

        try (InputStream first = source.open()) {
            first.readAllBytes();
        }

        try (InputStream second = source.open()) {
            assertThat(second).hasBinaryContent(CONTENT);
        }
    }

    @Test
    void byteArraySourceMayHaveNoName() {
        MediaSource source = MediaSource.of(new byte[0], null);

        assertThat(source.filename()).isNull();
        assertThat(source.size()).isZero();
    }

    @Test
    void fileSourceExposesItsGivenNameSizeAndContent(@TempDir Path directory) throws IOException {
        Path file = Files.write(directory.resolve("0199a3c4.csv"), CONTENT);

        MediaSource source = MediaSource.of(file, "tasks-2030-01-01.csv");

        assertThat(source.filename()).isEqualTo("tasks-2030-01-01.csv");
        assertThat(source.size()).isEqualTo(CONTENT.length);
        try (InputStream first = source.open(); InputStream second = source.open()) {
            assertThat(first).hasBinaryContent(CONTENT);
            assertThat(second).hasBinaryContent(CONTENT);
        }
    }

    @Test
    void fileSourceOfAMissingFileFailsOnSizeAndOnOpen(@TempDir Path directory) {
        MediaSource source = MediaSource.of(directory.resolve("missing.csv"), "tasks.csv");

        assertThatThrownBy(source::size)
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(source::open).isInstanceOf(NoSuchFileException.class);
    }
}
