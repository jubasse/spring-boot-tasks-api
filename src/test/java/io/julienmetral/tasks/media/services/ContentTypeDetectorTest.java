package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.model.MediaUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ContentTypeDetectorTest {

    private static final byte[] OLE2_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    private final ContentTypeDetector detector = new ContentTypeDetector();

    static Stream<Arguments> taskAttachmentSamples() {
        return Stream.of(
                arguments("photo.jpg", image("jpeg"), "image/jpeg"),
                arguments("photo.png", image("png"), "image/png"),
                arguments("animation.gif", image("gif"), "image/gif"),
                arguments("photo.webp", webp(), "image/webp"),
                arguments("report.pdf", pdf(), "application/pdf"),
                arguments("notes.txt", text("Meeting notes\nSecond line\n"), "text/plain"),
                arguments("data.csv", text("name,count\nalpha,1\nbeta,2\n"), "text/csv"),
                arguments("letter.rtf", text("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times;}} Hello}"),
                        "application/rtf"),
                arguments("letter.doc", ole2(), "application/msword"),
                arguments("budget.xls", ole2(), "application/vnd.ms-excel"),
                arguments("slides.ppt", ole2(), "application/vnd.ms-powerpoint"),
                arguments("letter.docx", ooxml("word/document.xml"),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                arguments("budget.xlsx", ooxml("xl/workbook.xml"),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                arguments("slides.pptx", ooxml("ppt/presentation.xml"),
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                arguments("letter.odt", odf("application/vnd.oasis.opendocument.text"),
                        "application/vnd.oasis.opendocument.text"),
                arguments("budget.ods", odf("application/vnd.oasis.opendocument.spreadsheet"),
                        "application/vnd.oasis.opendocument.spreadsheet"),
                arguments("slides.odp", odf("application/vnd.oasis.opendocument.presentation"),
                        "application/vnd.oasis.opendocument.presentation"),
                arguments("archive.zip", zip(), "application/zip")
        );
    }

    @ParameterizedTest(name = "{0} is detected as {2}")
    @MethodSource("taskAttachmentSamples")
    void everyAcceptedAttachmentTypeIsDetectedFromItsBytes(String filename, byte[] content, String expected)
            throws IOException {
        String detected = detect(content, filename);

        assertThat(detected).isEqualTo(expected);
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected)).isTrue();
    }

    @Test
    void markdownIsAnAcceptedAttachment() throws IOException {
        byte[] markdown = text("# Title\n\nSome *emphasis* and a [link](https://example.com).\n");

        String detected = detect(markdown, "README.md");

        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected))
                .as("detected type %s is accepted", detected)
                .isTrue();
    }

    @Test
    void odfIsDetectedFromItsMimetypeEntryWhateverItsName() throws IOException {
        assertThat(detect(odf("application/vnd.oasis.opendocument.text"), "upload.bin"))
                .isEqualTo("application/vnd.oasis.opendocument.text");
    }

    @Test
    void imagesKeepTheirRealTypeWhenNamedAsAnotherImage() throws IOException {
        assertThat(detect(image("png"), "photo.jpg")).isEqualTo("image/png");
    }

    @Test
    void executableNamedAsPdfIsNotAcceptedForAnyUsage() throws IOException {
        String detected = detect(windowsExecutable(), "invoice.pdf");

        assertThat(detected).isNotEqualTo("application/pdf");
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected)).isFalse();
        assertThat(MediaUsage.AVATAR.allows(detected)).isFalse();
    }

    @Test
    void htmlNamedAsPngIsNotAcceptedForAnyUsage() throws IOException {
        byte[] html = text("<!DOCTYPE html><html><body><script>alert(1)</script></body></html>");

        String detected = detect(html, "avatar.png");

        assertThat(detected).isEqualTo("text/html");
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected)).isFalse();
        assertThat(MediaUsage.AVATAR.allows(detected)).isFalse();
    }

    @Test
    void svgNamedAsPngIsNotAcceptedForAnyUsage() throws IOException {
        byte[] svg = text("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<script>alert(1)</script></svg>");

        String detected = detect(svg, "avatar.png");

        assertThat(detected).isNotEqualTo("image/png");
        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected)).isFalse();
        assertThat(MediaUsage.AVATAR.allows(detected)).isFalse();
    }

    @Test
    void zipNamedAsPdfStaysAZip() throws IOException {
        String detected = detect(zip(), "report.pdf");

        assertThat(detected).isEqualTo("application/zip");
        assertThat(MediaUsage.AVATAR.allows(detected)).isFalse();
    }

    @Test
    void plainZipNamedAsDocxIsTrustedAsWordDocument() throws IOException {
        assertThat(detect(zip(), "letter.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void ole2FileWithoutKnownExtensionIsNotAccepted() throws IOException {
        String detected = detect(ole2(), "upload.bin");

        assertThat(MediaUsage.TASK_ATTACHMENT.allows(detected)).isFalse();
    }

    private String detect(byte[] content, String filename) throws IOException {
        return detector.detect(new ByteArrayInputStream(content), filename);
    }

    private static byte[] image(String format) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xFF0000);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            assertThat(ImageIO.write(image, format, out)).as("ImageIO writer for " + format).isTrue();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return out.toByteArray();
    }

    private static byte[] webp() {
        byte[] frame = new byte[10];

        return ByteBuffer
                .allocate(12 + 8 + frame.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(US_ASCII))
                .putInt(4 + 8 + frame.length)
                .put("WEBP".getBytes(US_ASCII))
                .put("VP8 ".getBytes(US_ASCII))
                .putInt(frame.length)
                .put(frame)
                .array();
    }

    private static byte[] pdf() {
        return text("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n");
    }

    private static byte[] ole2() {
        byte[] content = new byte[512];
        System.arraycopy(OLE2_SIGNATURE, 0, content, 0, OLE2_SIGNATURE.length);
        return content;
    }

    private static byte[] windowsExecutable() {
        byte[] content = new byte[256];
        content[0] = 'M';
        content[1] = 'Z';
        byte[] stub = "This program cannot be run in DOS mode.".getBytes(US_ASCII);
        System.arraycopy(stub, 0, content, 78, stub.length);
        return content;
    }

    private static byte[] ooxml(String mainPart) {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>";

        return zipOf(
                new Entry("[Content_Types].xml", contentTypes, false),
                new Entry("_rels/.rels", "<Relationships/>", false),
                new Entry(mainPart, "<document/>", false)
        );
    }

    private static byte[] odf(String mimetype) {
        return zipOf(
                new Entry("mimetype", mimetype, true),
                new Entry("content.xml", "<office:document-content/>", false)
        );
    }

    private static byte[] zip() {
        return zipOf(new Entry("notes.txt", "hello", false));
    }

    private static byte[] text(String content) {
        return content.getBytes(UTF_8);
    }

    private record Entry(String name, String content, boolean stored) {
    }

    private static byte[] zipOf(Entry... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                byte[] bytes = entry.content().getBytes(UTF_8);
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
