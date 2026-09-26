package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Streams content to clamd with its INSTREAM command: {@code zINSTREAM\0}, then chunks each prefixed by their length
 * as a 4-byte big-endian integer, then a zero length. clamd answers {@code stream: OK}, {@code stream: <name> FOUND}
 * or an error line, terminated by a null byte.
 */
public class ClamAvScanner implements VirusScanner {

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

    private static final int CHUNK_SIZE = 64 * 1024;

    private static final String CLEAN = "stream: OK";

    private static final String PREFIX = "stream: ";

    private static final String FOUND = " FOUND";

    private final String host;

    private final int port;

    private final int timeoutMillis;

    public ClamAvScanner(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = Math.toIntExact(timeout.toMillis());
    }

    @Override
    public Optional<String> findThreat(InputStream content) {
        String reply;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            send(content, new DataOutputStream(socket.getOutputStream()));
            reply = readReply(socket.getInputStream());
        } catch (IOException exception) {
            throw new AntivirusUnavailableException(exception);
        }

        if (reply.equals(CLEAN)) {
            return Optional.empty();
        }

        if (reply.startsWith(PREFIX) && reply.endsWith(FOUND)) {
            return Optional.of(reply.substring(PREFIX.length(), reply.length() - FOUND.length()));
        }

        // For example "INSTREAM size limit exceeded. ERROR"
        throw new AntivirusUnavailableException("Unexpected clamd reply: " + reply);
    }

    private static void send(InputStream content, DataOutputStream out) throws IOException {
        out.write(INSTREAM);

        byte[] buffer = new byte[CHUNK_SIZE];
        int read;

        while ((read = content.read(buffer)) != -1) {
            out.writeInt(read);
            out.write(buffer, 0, read);
        }

        out.writeInt(0);
        out.flush();
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int next;

        while ((next = in.read()) != -1 && next != 0) {
            reply.write(next);
        }

        return reply.toString(StandardCharsets.US_ASCII).trim();
    }
}
