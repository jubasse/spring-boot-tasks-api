package io.julienmetral.tasks.identity.controllers;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarImageProcessingApiTests extends AbstractAvatarApiTests {

    private static final int AVATAR_SIDE = 256;

    // 1x1 lossless WebP: the JDK has no WebP reader, so this proves the ImageIO plugin is active
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

    @Test
    void opaquePngBecomesSquareJpeg() throws Exception {
        User user = createUser(UserRole.USER);

        byte[] stored = downloadAvatar(uploadOwnAvatar(user, png(filled(640, 480, Color.ORANGE))));

        assertThat(isJpeg(stored)).isTrue();
        assertThat(headObject(avatarStorageKey(user)).contentType()).isEqualTo("image/jpeg");

        BufferedImage avatar = decode(stored);

        assertThat(avatar.getWidth()).isEqualTo(AVATAR_SIDE);
        assertThat(avatar.getHeight()).isEqualTo(AVATAR_SIDE);
    }

    @Test
    void transparentPngStaysPngAndKeepsTransparency() throws Exception {
        User user = createUser(UserRole.USER);
        BufferedImage halfTransparent = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = halfTransparent.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(256, 0, 256, 512);
        graphics.dispose();

        byte[] stored = downloadAvatar(uploadOwnAvatar(user, png(halfTransparent)));

        assertThat(isPng(stored)).isTrue();
        assertThat(headObject(avatarStorageKey(user)).contentType()).isEqualTo("image/png");

        BufferedImage avatar = decode(stored);

        assertThat(avatar.getWidth()).isEqualTo(AVATAR_SIDE);
        assertThat(avatar.getHeight()).isEqualTo(AVATAR_SIDE);
        assertThat(alpha(avatar.getRGB(32, 128))).isZero();
        assertThat(alpha(avatar.getRGB(224, 128))).isEqualTo(255);
        assertThat(new Color(avatar.getRGB(224, 128))).isEqualTo(Color.RED);
    }

    @Test
    void wideImageIsCroppedToItsCentre() throws Exception {
        User user = createUser(UserRole.USER);
        BufferedImage bands = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bands.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 300, 300);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(300, 0, 300, 300);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(600, 0, 300, 300);
        graphics.dispose();

        BufferedImage avatar = decode(downloadAvatar(uploadOwnAvatar(user, png(bands))));

        assertThat(avatar.getWidth()).isEqualTo(AVATAR_SIDE);
        assertThat(isGreen(avatar.getRGB(4, 128))).isTrue();
        assertThat(isGreen(avatar.getRGB(251, 128))).isTrue();
    }

    @Test
    void exifOrientationIsAppliedAndMetadataDropped() throws Exception {
        User user = createUser(UserRole.USER);
        byte[] original = withExif(jpeg(redTopLeftQuadrant()));
        Metadata originalMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));

        assertThat(originalMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class)
                .getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);
        assertThat(originalMetadata.getFirstDirectoryOfType(GpsDirectory.class)
                .getString(GpsDirectory.TAG_LATITUDE_REF)).isEqualTo("N");

        String body = uploadAvatar(user, asUser(user), "IMG_0001.JPG", original)
                .andReturn()
                .getResponse()
                .getContentAsString();
        byte[] stored = downloadAvatar(json(body).get("avatarUrl").asString());

        assertThat(isJpeg(stored)).isTrue();

        BufferedImage avatar = decode(stored);

        assertThat(avatar.getWidth()).isEqualTo(AVATAR_SIDE);
        assertThat(isRed(avatar.getRGB(192, 64))).as("top-right after a 90 degree clockwise turn").isTrue();
        assertThat(isBlue(avatar.getRGB(64, 64))).isTrue();
        assertThat(isBlue(avatar.getRGB(64, 192))).isTrue();
        assertThat(isBlue(avatar.getRGB(192, 192))).isTrue();

        Metadata storedMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(stored));

        assertThat(storedMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        assertThat(storedMetadata.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
    }

    @Test
    void webpIsAccepted() throws Exception {
        User user = createUser(UserRole.USER);

        String body = uploadAvatar(user, asUser(user), "photo.webp", WEBP)
                .andReturn()
                .getResponse()
                .getContentAsString();
        BufferedImage avatar = decode(downloadAvatar(json(body).get("avatarUrl").asString()));

        assertThat(avatar.getWidth()).isEqualTo(AVATAR_SIDE);
        assertThat(avatar.getHeight()).isEqualTo(AVATAR_SIDE);
    }

    private byte[] downloadAvatar(String avatarUrl) throws Exception {
        HttpResponse<byte[]> response = download(avatarUrl);

        assertThat(response.statusCode()).isEqualTo(200);

        return response.body();
    }

    private static BufferedImage redTopLeftQuadrant() {
        BufferedImage image = filled(512, 512, Color.BLUE);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();

        return image;
    }

    /**
     * Inserts, after the JFIF segment, an EXIF APP1 segment with orientation 6 (stored sideways, to be turned 90
     * degrees clockwise) and a GPS directory. Big-endian TIFF layout: IFD0 at offset 8, GPS IFD right after it.
     */
    private static byte[] withExif(byte[] jpeg) throws IOException {
        int gpsIfdOffset = 8 + 2 + 2 * 12 + 4;

        ByteBuffer tiff = ByteBuffer.allocate(gpsIfdOffset + 2 + 2 * 12 + 4)
                .put(new byte[]{'M', 'M', 0, 42})
                .putInt(8)
                .putShort((short) 2)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0)
                .putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfdOffset)
                .putInt(0)
                .putShort((short) 2)
                .putShort((short) 0x0000).putShort((short) 1).putInt(4).put(new byte[]{2, 3, 0, 0})
                .putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[]{'N', 0, 0, 0})
                .putInt(0);

        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int segmentLength = 2 + exifHeader.length + tiff.capacity();

        int insertAt = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            insertAt += 2 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, insertAt);
        output.write(0xFF);
        output.write(0xE1);
        output.write(segmentLength >> 8);
        output.write(segmentLength & 0xFF);
        output.write(exifHeader);
        output.write(tiff.array());
        output.write(jpeg, insertAt, jpeg.length - insertAt);

        return output.toByteArray();
    }

    private static BufferedImage decode(byte[] image) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));

        assertThat(decoded).isNotNull();

        return decoded;
    }

    private static boolean isJpeg(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    private static boolean isPng(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static boolean isRed(int rgb) {
        Color color = new Color(rgb);
        return color.getRed() > 200 && color.getGreen() < 60 && color.getBlue() < 60;
    }

    private static boolean isGreen(int rgb) {
        Color color = new Color(rgb);
        return color.getGreen() > 200 && color.getRed() < 60 && color.getBlue() < 60;
    }

    private static boolean isBlue(int rgb) {
        Color color = new Color(rgb);
        return color.getBlue() > 200 && color.getRed() < 60 && color.getGreen() < 60;
    }
}
