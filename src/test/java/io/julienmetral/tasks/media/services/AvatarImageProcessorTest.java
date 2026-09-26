package io.julienmetral.tasks.media.services;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.model.ProcessedImage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class AvatarImageProcessorTest {

    private static final int SIDE = AvatarImageProcessor.SIZE;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static final short TIFF_SHORT = 3;
    private static final short TIFF_LONG = 4;
    private static final short TIFF_ASCII = 2;

    private final AvatarImageProcessor processor = new AvatarImageProcessor();

    /** Top-left red, top-right green, bottom half blue: two adjacent corners tell all 8 orientations apart. */
    private static BufferedImage corners(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(width / 2, 0, width - width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    private static BufferedImage filled(int width, int height, int imageType, Color color) {
        BufferedImage image = new BufferedImage(width, height, imageType);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(image, format, bytes)).isTrue();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Inserts an APP1 segment carrying the TIFF structure right after the JPEG start-of-image marker. */
    private static byte[] withExif(byte[] jpeg, byte[] tiff) {
        byte[] exifHeader = "Exif\0\0".getBytes(US_ASCII);
        int segmentLength = 2 + exifHeader.length + tiff.length;

        return ByteBuffer.allocate(jpeg.length + 2 + segmentLength)
                .put(jpeg, 0, 2)
                .put((byte) 0xFF).put((byte) 0xE1)
                .putShort((short) segmentLength)
                .put(exifHeader)
                .put(tiff)
                .put(jpeg, 2, jpeg.length - 2)
                .array();
    }

    /** Big-endian TIFF structure whose first IFD holds a single entry with an inline value. */
    private static byte[] singleEntryTiff(int tag, short type, int count, byte[] inlineValue) {
        return ByteBuffer.allocate(8 + 2 + 12 + 4)
                .put("MM".getBytes(US_ASCII)).putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) tag).putShort(type).putInt(count).put(inlineValue)
                .putInt(0)
                .array();
    }

    private static byte[] orientationTiff(int orientation) {
        return singleEntryTiff(ExifIFD0Directory.TAG_ORIENTATION, TIFF_SHORT, 1,
                ByteBuffer.allocate(4).putShort((short) orientation).array());
    }

    /** Orientation 6 plus a GPS IFD holding a latitude reference, as a phone camera would write. */
    private static byte[] orientationAndGpsTiff() {
        int gpsIfdOffset = 8 + 2 + 2 * 12 + 4;

        return ByteBuffer.allocate(gpsIfdOffset + 2 + 12 + 4)
                .put("MM".getBytes(US_ASCII)).putShort((short) 42).putInt(8)
                .putShort((short) 2)
                .putShort((short) ExifIFD0Directory.TAG_ORIENTATION).putShort(TIFF_SHORT).putInt(1)
                .putShort((short) 6).putShort((short) 0)
                .putShort((short) ExifIFD0Directory.TAG_GPS_INFO_OFFSET).putShort(TIFF_LONG).putInt(1)
                .putInt(gpsIfdOffset)
                .putInt(0)
                .putShort((short) 1)
                .putShort((short) GpsDirectory.TAG_LATITUDE_REF).putShort(TIFF_ASCII).putInt(2)
                .put("N\0\0\0".getBytes(US_ASCII))
                .putInt(0)
                .array();
    }

    private static byte[] jpegWithOrientation(BufferedImage image, int orientation) {
        return withExif(encode(image, "jpg"), orientationTiff(orientation));
    }

    /** A PNG made of its signature, an IHDR chunk and IEND only: its header is valid, its pixels are missing. */
    private static byte[] pngHeaderOnly(int width, int height) {
        byte[] ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                .array();
        byte[] ihdrChunk = chunk("IHDR", ihdr);
        byte[] iendChunk = chunk("IEND", new byte[0]);

        return ByteBuffer.allocate(PNG_SIGNATURE.length + ihdrChunk.length + iendChunk.length)
                .put(PNG_SIGNATURE).put(ihdrChunk).put(iendChunk)
                .array();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);

        return ByteBuffer.allocate(4 + 4 + data.length + 4)
                .putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue())
                .array();
    }

    /** Dominant channel of a pixel, lossy compression notwithstanding: R, G or B. */
    private static char colourAt(BufferedImage image, int x, int y) {
        Color pixel = new Color(image.getRGB(x, y));
        int red = pixel.getRed();
        int green = pixel.getGreen();
        int blue = pixel.getBlue();

        if (red > green && red > blue) {
            return 'R';
        }
        return green > blue ? 'G' : 'B';
    }

    /** Colours of the four quadrant centres: top-left, top-right, bottom-left, bottom-right. */
    private static String quadrants(BufferedImage image) {
        int quarter = image.getWidth() / 4;
        int threeQuarters = image.getWidth() - quarter;

        return "" + colourAt(image, quarter, quarter) + colourAt(image, threeQuarters, quarter)
                + colourAt(image, quarter, threeQuarters) + colourAt(image, threeQuarters, threeQuarters);
    }

    private static Metadata metadata(byte[] image) throws Exception {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
    }

    @Nested
    class Orientation {

        @ParameterizedTest(name = "orientation {0} displays as {1}")
        @CsvSource({
                "1, RGBB",
                "2, GRBB",
                "3, BBGR",
                "4, BBRG",
                "5, RBGB",
                "6, BRBG",
                "7, BGBR",
                "8, GBRB"
        })
        void exifOrientationIsApplied(int orientation, String expectedQuadrants) {
            byte[] original = jpegWithOrientation(corners(64, 64), orientation);

            ProcessedImage processed = processor.process(original);

            assertThat(quadrants(decode(processed.content()))).isEqualTo(expectedQuadrants);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        void exifOrientationIsRead(int orientation) {
            assertThat(AvatarImageProcessor.exifOrientation(jpegWithOrientation(corners(8, 8), orientation)))
                    .isEqualTo(orientation);
        }

        @Test
        void jpegWithoutExifIsTakenAsStored() {
            byte[] original = encode(corners(64, 64), "jpg");

            assertThat(AvatarImageProcessor.exifOrientation(original)).isEqualTo(1);
            assertThat(quadrants(decode(processor.process(original).content()))).isEqualTo("RGBB");
        }

        @Test
        void pngIsTakenAsStored() {
            assertThat(AvatarImageProcessor.exifOrientation(encode(corners(8, 8), "png"))).isEqualTo(1);
        }

        @Test
        void exifWithoutOrientationIsTakenAsStored() {
            byte[] original = withExif(encode(corners(8, 8), "jpg"),
                    singleEntryTiff(ExifIFD0Directory.TAG_MAKE, TIFF_ASCII, 4, "Cam\0".getBytes(US_ASCII)));

            assertThat(AvatarImageProcessor.exifOrientation(original)).isEqualTo(1);
        }

        @Test
        void unreadableMetadataIsTakenAsStored() {
            assertThat(AvatarImageProcessor.exifOrientation("not an image".getBytes(US_ASCII))).isEqualTo(1);
        }

        @Test
        void orientationThatIsNotASingleNumberIsTakenAsStored() {
            byte[] twoValues = ByteBuffer.allocate(4).putShort((short) 6).putShort((short) 6).array();
            byte[] original = withExif(encode(corners(8, 8), "jpg"),
                    singleEntryTiff(ExifIFD0Directory.TAG_ORIENTATION, TIFF_SHORT, 2, twoValues));

            assertThat(AvatarImageProcessor.exifOrientation(original)).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 9, 255})
        void outOfRangeOrientationLeavesTheImageAsStored(int orientation) {
            BufferedImage image = corners(40, 20);

            assertThat(AvatarImageProcessor.orient(image, orientation)).isSameAs(image);
            assertThat(quadrants(decode(processor.process(jpegWithOrientation(corners(64, 64), orientation))
                    .content()))).isEqualTo("RGBB");
        }

        @ParameterizedTest
        @ValueSource(ints = {5, 6, 7, 8})
        void quarterTurnsSwapWidthAndHeight(int orientation) {
            BufferedImage oriented = AvatarImageProcessor.orient(corners(40, 20), orientation);

            assertThat(oriented.getWidth()).isEqualTo(20);
            assertThat(oriented.getHeight()).isEqualTo(40);
        }

        @ParameterizedTest
        @ValueSource(ints = {2, 3, 4})
        void mirrorsAndHalfTurnKeepWidthAndHeight(int orientation) {
            BufferedImage oriented = AvatarImageProcessor.orient(corners(40, 20), orientation);

            assertThat(oriented.getWidth()).isEqualTo(40);
            assertThat(oriented.getHeight()).isEqualTo(20);
        }

        @Test
        void quarterTurnOfARectangleLeavesNoUnpaintedPixel() {
            BufferedImage oriented = AvatarImageProcessor.orient(filled(41, 20, BufferedImage.TYPE_INT_RGB,
                    Color.RED), 6);

            for (int x = 0; x < oriented.getWidth(); x++) {
                for (int y = 0; y < oriented.getHeight(); y++) {
                    assertThat(oriented.getRGB(x, y) >>> 24).as("alpha at %d,%d", x, y).isEqualTo(0xFF);
                }
            }
        }
    }

    @Nested
    class Geometry {

        @Test
        void landscapeIsCroppedToItsCentre() {
            BufferedImage stored = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = stored.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 300, 300);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(300, 0, 300, 300);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(600, 0, 300, 300);
            graphics.dispose();

            BufferedImage result = decode(processor.process(encode(stored, "png")).content());

            assertThat(result.getWidth()).isEqualTo(SIDE);
            assertThat(result.getHeight()).isEqualTo(SIDE);
            assertThat(quadrants(result)).isEqualTo("GGGG");
            assertThat(colourAt(result, 2, SIDE / 2)).isEqualTo('G');
            assertThat(colourAt(result, SIDE - 3, SIDE / 2)).isEqualTo('G');
        }

        @Test
        void portraitIsCroppedToItsCentre() {
            BufferedImage stored = new BufferedImage(300, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = stored.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 300, 300);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(0, 300, 300, 300);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 600, 300, 300);
            graphics.dispose();

            BufferedImage result = decode(processor.process(encode(stored, "png")).content());

            assertThat(result.getWidth()).isEqualTo(SIDE);
            assertThat(result.getHeight()).isEqualTo(SIDE);
            assertThat(quadrants(result)).isEqualTo("GGGG");
            assertThat(colourAt(result, SIDE / 2, 2)).isEqualTo('G');
            assertThat(colourAt(result, SIDE / 2, SIDE - 3)).isEqualTo('G');
        }

        @Test
        void cropKeepsTheCentredSquareOfAnOddDifference() {
            BufferedImage cropped = AvatarImageProcessor.cropToSquare(corners(11, 4));

            assertThat(cropped.getWidth()).isEqualTo(4);
            assertThat(cropped.getHeight()).isEqualTo(4);
            assertThat(cropped.getMinX()).isZero();
        }

        @Test
        void squareIsNotCropped() {
            BufferedImage cropped = AvatarImageProcessor.cropToSquare(corners(10, 10));

            assertThat(cropped.getWidth()).isEqualTo(10);
            assertThat(quadrants(cropped)).isEqualTo("RGBB");
        }

        @Test
        void largePhotoIsScaledDownTo256() {
            BufferedImage result = decode(processor.process(encode(corners(1100, 1100), "jpg")).content());

            assertThat(result.getWidth()).isEqualTo(SIDE);
            assertThat(result.getHeight()).isEqualTo(SIDE);
            assertThat(quadrants(result)).isEqualTo("RGBB");
        }

        @Test
        void smallPhotoIsScaledUpTo256() {
            BufferedImage result = decode(processor.process(encode(corners(16, 16), "png")).content());

            assertThat(result.getWidth()).isEqualTo(SIDE);
            assertThat(result.getHeight()).isEqualTo(SIDE);
            assertThat(quadrants(result)).isEqualTo("RGBB");
        }

        @Test
        void halvingThatLandsOnTheTargetNeedsNoLastStep() {
            BufferedImage scaled = AvatarImageProcessor.scaleDown(corners(1024, 1024), SIDE);

            assertThat(scaled.getWidth()).isEqualTo(SIDE);
            assertThat(scaled.getHeight()).isEqualTo(SIDE);
            assertThat(quadrants(scaled)).isEqualTo("RGBB");
        }

        @Test
        void imageAlreadyAtTheTargetIsReturnedAsIs() {
            BufferedImage image = corners(SIDE, SIDE);

            assertThat(AvatarImageProcessor.scaleDown(image, SIDE)).isSameAs(image);
        }

        @Test
        void imageBetweenOnceAndTwiceTheTargetIsResizedInOneStep() {
            BufferedImage scaled = AvatarImageProcessor.scaleDown(corners(511, 511), SIDE);

            assertThat(scaled.getWidth()).isEqualTo(SIDE);
        }
    }

    @Nested
    class Encoding {

        @Test
        void opaqueJpegGivesAJpeg() {
            ProcessedImage processed = processor.process(encode(corners(64, 64), "jpg"));

            assertThat(processed.contentType()).isEqualTo("image/jpeg");
            assertThat(processed.extension()).isEqualTo("jpg");
            assertThat(processed.content()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            BufferedImage decoded = decode(processed.content());
            assertThat(decoded.getColorModel().hasAlpha()).isFalse();
            assertThat(decoded.getWidth()).isEqualTo(SIDE);
        }

        @Test
        void opaquePngGivesAJpeg() {
            ProcessedImage processed = processor.process(encode(corners(64, 64), "png"));

            assertThat(processed.contentType()).isEqualTo("image/jpeg");
            assertThat(processed.extension()).isEqualTo("jpg");
        }

        @Test
        void rotatedOpaquePhotoStaysAJpeg() {
            ProcessedImage processed = processor.process(jpegWithOrientation(corners(64, 64), 6));

            assertThat(processed.contentType()).isEqualTo("image/jpeg");
        }

        @Test
        void pngWithAlphaGivesAPngKeepingTransparency() {
            BufferedImage stored = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = stored.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(32, 0, 32, 64);
            graphics.dispose();

            ProcessedImage processed = processor.process(encode(stored, "png"));

            assertThat(processed.contentType()).isEqualTo("image/png");
            assertThat(processed.extension()).isEqualTo("png");
            assertThat(processed.content()).startsWith(PNG_SIGNATURE);
            BufferedImage decoded = decode(processed.content());
            assertThat(decoded.getWidth()).isEqualTo(SIDE);
            assertThat(decoded.getHeight()).isEqualTo(SIDE);
            assertThat(decoded.getColorModel().hasAlpha()).isTrue();
            assertThat(decoded.getRGB(10, SIDE / 2) >>> 24).isZero();
            assertThat(decoded.getRGB(SIDE - 10, SIDE / 2)).isEqualTo(Color.RED.getRGB());
        }

        @Test
        void greyscaleJpegGivesAColourJpeg() {
            BufferedImage grey = filled(300, 300, BufferedImage.TYPE_BYTE_GRAY, Color.GRAY);

            ProcessedImage processed = processor.process(jpegWithOrientation(grey, 6));

            assertThat(processed.contentType()).isEqualTo("image/jpeg");
            assertThat(decode(processed.content()).getWidth()).isEqualTo(SIDE);
        }

        @Test
        void palettePngWithATransparentEntryGivesAPng() {
            BufferedImage indexed = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_BYTE_INDEXED,
                    new IndexColorModel(8, 2,
                            new byte[]{0, (byte) 0xFF}, new byte[]{0, 0}, new byte[]{0, 0}, 0));

            ProcessedImage processed = processor.process(encode(indexed, "png"));

            assertThat(processed.contentType()).isEqualTo("image/png");
            BufferedImage decoded = decode(processed.content());
            assertThat(decoded.getWidth()).isEqualTo(SIDE);
            assertThat(decoded.getRGB(SIDE / 2, SIDE / 2) >>> 24).isZero();
        }

        @Test
        void outputCarriesNoExifNorGpsFromTheOriginal() throws Exception {
            byte[] original = withExif(encode(corners(64, 64), "jpg"), orientationAndGpsTiff());
            Metadata before = metadata(original);
            assertThat(before.getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
            assertThat(before.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNotNull();

            ProcessedImage processed = processor.process(original);

            Metadata after = metadata(processed.content());
            assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
            assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
            assertThat(quadrants(decode(processed.content()))).isEqualTo("BRBG");
        }

        @Test
        void webpIsDecoded() {
            // 1x1 lossy WebP, the smallest valid file; no WebP encoder is available to build a larger one
            byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

            ProcessedImage processed = processor.process(webp);

            assertThat(processed.contentType()).isEqualTo("image/jpeg");
            BufferedImage decoded = decode(processed.content());
            assertThat(decoded.getWidth()).isEqualTo(SIDE);
            assertThat(decoded.getHeight()).isEqualTo(SIDE);
        }
    }

    @Nested
    class Rejection {

        @Test
        void declaredDimensionBombIsRejectedBeforeDecoding() {
            byte[] bomb = pngHeaderOnly(50_000, 50_000);

            assertThat(bomb).hasSizeLessThan(100);
            assertThatThrownBy(() -> processor.process(bomb))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: dimensions 50000x50000 are too large");
        }

        @ParameterizedTest
        @CsvSource({"10001, 1", "1, 10001", "7000, 7000"})
        void imageAboveTheSideOrPixelLimitIsRejected(int width, int height) {
            assertThatThrownBy(() -> processor.process(pngHeaderOnly(width, height)))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: dimensions " + width + "x" + height + " are too large");
        }

        @Test
        void imageAtTheSideLimitPassesTheDimensionCheck() {
            assertThatThrownBy(() -> processor.process(pngHeaderOnly(AvatarImageProcessor.MAX_SIDE, 1)))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: unreadable image");
        }

        @Test
        void bytesOfNoKnownFormatAreRejected() {
            assertThatThrownBy(() -> processor.process("definitely not an image".getBytes(US_ASCII)))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: unreadable image");
        }

        @Test
        void emptyContentIsRejected() {
            assertThatThrownBy(() -> processor.process(new byte[0]))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: unreadable image");
        }

        @Test
        void truncatedHeaderIsRejected() {
            byte[] truncated = ByteBuffer.allocate(PNG_SIGNATURE.length + 11)
                    .put(PNG_SIGNATURE).putInt(13).put("IHDR".getBytes(US_ASCII)).put(new byte[3])
                    .array();

            assertThatThrownBy(() -> processor.process(truncated))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: unreadable image");
        }

        @Test
        void validHeaderWithoutPixelsIsRejected() {
            assertThatThrownBy(() -> processor.process(pngHeaderOnly(10, 10)))
                    .isInstanceOf(InvalidImageException.class)
                    .hasMessage("The image cannot be used: unreadable image");
        }

        @Test
        void imageNoDecoderCanReadIsRejected() {
            byte[] png = encode(corners(8, 8), "png");

            try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class, Mockito.CALLS_REAL_METHODS)) {
                imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(null);

                assertThatThrownBy(() -> processor.process(png))
                        .isInstanceOf(InvalidImageException.class)
                        .hasMessage("The image cannot be used: unreadable image");
            }
        }

        @Test
        void encodingFailureIsRethrownUnchecked() {
            byte[] png = encode(corners(8, 8), "png");
            IOException failure = new IOException("no output stream");

            try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class, Mockito.CALLS_REAL_METHODS)) {
                imageIo.when(() -> ImageIO.createImageOutputStream(any())).thenThrow(failure);

                assertThatThrownBy(() -> processor.process(png))
                        .isInstanceOf(UncheckedIOException.class)
                        .hasCause(failure);
            }
        }
    }
}
