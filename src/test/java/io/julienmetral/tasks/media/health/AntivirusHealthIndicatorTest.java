package io.julienmetral.tasks.media.health;

import io.julienmetral.tasks.config.AntivirusProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AntivirusHealthIndicatorTest {

    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(60);

    private FakeClamd clamd;

    @AfterEach
    void stopClamd() throws IOException {
        if (clamd != null) {
            clamd.close();
        }
    }

    @Test
    void disabledScanningIsUpWithoutAskingClamd() throws IOException {
        // Nothing listens on that port: a PING would report the antivirus down
        AntivirusProperties disabled = new AntivirusProperties(false, LOOPBACK, closedPort(), SCAN_TIMEOUT);

        Health health = new AntivirusHealthIndicator(disabled).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEqualTo(Map.of("scanning", "disabled"));
    }

    @Test
    void sendsTheNullTerminatedPingCommand() throws Exception {
        clamd = FakeClamd.replying("PONG\0");

        health(SCAN_TIMEOUT);

        assertThat(clamd.command()).isEqualTo("zPING\0");
    }

    @Test
    void pongIsUpWithoutDetails() {
        clamd = FakeClamd.replying("PONG\0");

        Health health = health(SCAN_TIMEOUT);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PONG", "PONG\n\0", " PONG \0"})
    void pongEndedByTheConnectionOrSurroundedByWhitespaceIsUp(String reply) {
        clamd = FakeClamd.replying(reply);

        assertThat(health(SCAN_TIMEOUT).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void bytesAfterTheNullTerminatorAreIgnored() {
        clamd = FakeClamd.replying("PONG\0UNKNOWN COMMAND\0");

        assertThat(health(SCAN_TIMEOUT).getStatus()).isEqualTo(Status.UP);
    }

    static Stream<Arguments> otherReplies() {
        return Stream.of(
                Arguments.of("UNKNOWN COMMAND\0", "UNKNOWN COMMAND"),
                Arguments.of("pong\0", "pong"),
                Arguments.of("PONGED\0", "PONGED"),
                Arguments.of("\0", "")
        );
    }

    @ParameterizedTest
    @MethodSource("otherReplies")
    void anyOtherReplyIsDownWithTheReply(String reply, String reported) {
        clamd = FakeClamd.replying(reply);

        Health health = health(SCAN_TIMEOUT);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEqualTo(Map.of("reply", reported));
    }

    @Test
    void connectionClosedWithoutAReplyIsDownWithAnEmptyReply() {
        clamd = FakeClamd.replying("");

        Health health = health(SCAN_TIMEOUT);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEqualTo(Map.of("reply", ""));
    }

    @Test
    void refusedConnectionIsDownWithTheError() throws IOException {
        AntivirusProperties properties = new AntivirusProperties(true, LOOPBACK, closedPort(), SCAN_TIMEOUT);

        Health health = new AntivirusHealthIndicator(properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error");
        assertThat(health.getDetails().get("error").toString()).startsWith(ConnectException.class.getName());
    }

    @Test
    void unknownHostIsDownWithTheError() {
        AntivirusProperties properties = new AntivirusProperties(true, "clamd.invalid", 3310, SCAN_TIMEOUT);

        Health health = new AntivirusHealthIndicator(properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error");
    }

    @Test
    void silentClamdIsDownOnceTheConfiguredTimeoutExpires() {
        clamd = FakeClamd.silent();

        long start = System.nanoTime();
        Health health = health(Duration.ofMillis(300));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString()).startsWith(SocketTimeoutException.class.getName());
    }

    @Test
    void silentClamdIsDownAfterFiveSecondsEvenWhenTheScanTimeoutIsLonger() {
        clamd = FakeClamd.silent();

        long start = System.nanoTime();
        Health health = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> health(SCAN_TIMEOUT));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsed).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(10));
    }

    private Health health(Duration timeout) {
        return new AntivirusHealthIndicator(new AntivirusProperties(true, LOOPBACK, clamd.port(), timeout)).health();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** One-connection clamd stand-in: records the command up to its null byte, then answers with raw bytes. */
    static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;

        private final CompletableFuture<String> command = new CompletableFuture<>();

        private final CountDownLatch release = new CountDownLatch(1);

        private FakeClamd(byte[] reply) {
            try {
                server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            Thread.ofVirtual().start(() -> serve(reply));
        }

        static FakeClamd replying(String reply) {
            return new FakeClamd(reply.getBytes(StandardCharsets.US_ASCII));
        }

        static FakeClamd silent() {
            return new FakeClamd(null);
        }

        int port() {
            return server.getLocalPort();
        }

        String command() throws Exception {
            return command.get(5, TimeUnit.SECONDS);
        }

        private void serve(byte[] reply) {
            try (Socket socket = server.accept()) {
                command.complete(readCommand(socket.getInputStream()));

                if (reply == null) {
                    release.await();
                    return;
                }

                OutputStream out = socket.getOutputStream();
                out.write(reply);
                out.flush();
            } catch (IOException | InterruptedException exception) {
                command.completeExceptionally(exception);
            }
        }

        private static String readCommand(InputStream in) throws IOException {
            ByteArrayOutputStream command = new ByteArrayOutputStream();
            int next;

            do {
                next = in.read();
                if (next != -1) {
                    command.write(next);
                }
            } while (next != -1 && next != 0);

            return command.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            release.countDown();
            server.close();
        }
    }
}
