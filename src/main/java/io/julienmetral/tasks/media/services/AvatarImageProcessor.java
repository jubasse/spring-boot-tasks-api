package io.julienmetral.tasks.media.services;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import io.julienmetral.tasks.media.exceptions.InvalidImageException;
import io.julienmetral.tasks.media.model.ProcessedImage;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Turns an uploaded photo into a square profile picture: EXIF orientation applied, centre-cropped, scaled down and
 * re-encoded. Re-encoding is what strips the metadata (GPS position, camera, ...), so the original bytes are never
 * served.
 */
@Component
public class AvatarImageProcessor {

    static final int SIZE = 256;

    // Checked before decoding: a few kilobytes can declare a 50000 x 50000 image and exhaust the heap when decoded
    static final int MAX_SIDE = 10_000;

    static final long MAX_PIXELS = 40_000_000L;

    private static final float JPEG_QUALITY = 0.85f;

    public ProcessedImage process(byte[] original) {
        checkDimensions(original);

        BufferedImage image = decode(original);

        // Decided on the decoded original: the transformations below always produce an image with an alpha channel
        boolean transparent = image.getColorModel().hasAlpha();

        image = orient(image, exifOrientation(original));
        image = cropToSquare(image);
        image = scaleDown(image, SIZE);

        return transparent
                ? new ProcessedImage(encode(image, "png", null), "image/png", "png")
                : new ProcessedImage(encode(opaque(image), "jpeg", JPEG_QUALITY), "image/jpeg", "jpg");
    }

    /**
     * Reads only the image header, so it is cheap enough to run on upload: an oversized image is rejected at once
     * instead of by the worker.
     *
     * @throws InvalidImageException when the image cannot be read or is larger than the limits
     */
    public void checkDimensions(byte[] original) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw new InvalidImageException("unreadable image");
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw new InvalidImageException("dimensions " + width + "x" + height + " are too large");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new InvalidImageException("unreadable image");
        }
    }

    private static BufferedImage decode(byte[] original) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));

            if (image == null) {
                throw new InvalidImageException("unreadable image");
            }

            return image;
        } catch (IOException exception) {
            throw new InvalidImageException("unreadable image");
        }
    }

    /** EXIF orientation 1 to 8; 1 (as stored) when absent or unreadable. */
    static int exifOrientation(byte[] original) {
        try {
            var directory = ImageMetadataReader
                    .readMetadata(new ByteArrayInputStream(original))
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);

            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }

            return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (ImageProcessingException | MetadataException | IOException exception) {
            return 1;
        }
    }

    /** Applies the rotation and mirroring that cameras record in EXIF instead of rotating the pixels. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean swapsSides = orientation >= 5 && orientation <= 8;

        AffineTransform transform = new AffineTransform();

        switch (orientation) {
            case 2 -> transform.scale(-1, 1);
            case 3 -> transform.rotate(Math.PI);
            case 4 -> transform.scale(1, -1);
            case 5 -> {
                transform.rotate(Math.PI / 2);
                transform.scale(1, -1);
            }
            case 6 -> transform.rotate(Math.PI / 2);
            case 7 -> {
                transform.rotate(-Math.PI / 2);
                transform.scale(1, -1);
            }
            case 8 -> transform.rotate(-Math.PI / 2);
            default -> {
                return image;
            }
        }

        int targetWidth = swapsSides ? height : width;
        int targetHeight = swapsSides ? width : height;

        // Rotate around the centre, then move the result back into the positive quadrant
        AffineTransform centred = new AffineTransform();
        centred.translate(targetWidth / 2.0, targetHeight / 2.0);
        centred.concatenate(transform);
        centred.translate(-width / 2.0, -height / 2.0);

        BufferedImage oriented = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = oriented.createGraphics();

        graphics.drawImage(image, centred, null);
        graphics.dispose();

        return oriented;
    }

    static BufferedImage cropToSquare(BufferedImage image) {
        int side = Math.min(image.getWidth(), image.getHeight());
        int x = (image.getWidth() - side) / 2;
        int y = (image.getHeight() - side) / 2;

        return image.getSubimage(x, y, side, side);
    }

    /** Halves the image until close to the target, then a last bicubic step: smoother than one large jump. */
    static BufferedImage scaleDown(BufferedImage image, int target) {
        BufferedImage current = image;

        while (current.getWidth() / 2 >= target) {
            current = resize(current, current.getWidth() / 2);
        }

        return current.getWidth() == target ? current : resize(current, target);
    }

    private static BufferedImage resize(BufferedImage image, int side) {
        BufferedImage resized = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, 0, 0, side, side, null);
        graphics.dispose();

        return resized;
    }

    // JPEG has no alpha channel: flatten on white instead of letting transparent pixels turn black
    private static BufferedImage opaque(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();

        return rgb;
    }

    private static byte[] encode(BufferedImage image, String format, Float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);

            ImageWriteParam param = writer.getDefaultWriteParam();

            if (quality != null) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }

            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            writer.dispose();
        }

        return bytes.toByteArray();
    }
}
