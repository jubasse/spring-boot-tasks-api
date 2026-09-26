package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ClamAvScannerTest {

    private static final int CHUNK_SIZE = 64 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final byte[] CONTENT = "some file content".getBytes(StandardCharsets.US_ASCII);

    private FakeClamd clamd;

    @AfterEach
    void stopClamd() throws IOException {
        if (clamd != null) {
            clamd.close();
        }
    }

    private ClamAvScanner scanner(Duration timeout) {
        return new ClamAvScanner(InetAddress.getLoopbackAddress().getHostAddress(), clamd.port(), timeout);
    }

    private Optional<String> scan(byte[] content) {
        return scanner(TIMEOUT).findThreat(new ByteArrayInputStream(content));
    }

    @Nested
    class WireFormat {

        @Test
        void sendsTheInstreamCommandThenOneChunkThenAZeroLength() throws Exception {
            clamd = FakeClamd.replying("stream: OK\0");

            scan(CONTENT);

            Request request = clamd.request();
            assertThat(request.command()).isEqualTo("zINSTREAM\0");
            assertThat(request.chunkLengths()).containsExactly(CONTENT.length);
            assertThat(request.content()).isEqualTo(CONTENT);
            assertThat(request.terminated()).isTrue();
        }

        @Test
        void splitsContentLargerThan64KibIntoChunksOfAtMost64Kib() throws Exception {
            byte[] content = new byte[2 * CHUNK_SIZE + 123];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i * 31);
            }
            clamd = FakeClamd.replying("stream: OK\0");

            scan(content);

            Request request = clamd.request();
            assertThat(request.chunkLengths()).containsExactly(CHUNK_SIZE, CHUNK_SIZE, 123);
            assertThat(request.content()).isEqualTo(content);
            assertThat(request.terminated()).isTrue();
        }

        @Test
        void contentOfExactly64KibIsOneChunk() throws Exception {
            byte[] content = new byte[CHUNK_SIZE];
            clamd = FakeClamd.replying("stream: OK\0");

            scan(content);

            assertThat(clamd.request().chunkLengths()).containsExactly(CHUNK_SIZE);
        }

        @Test
        void emptyContentSendsOnlyTheZeroLength() throws Exception {
            clamd = FakeClamd.replying("stream: OK\0");

            scan(new byte[0]);

            Request request = clamd.request();
            assertThat(request.command()).isEqualTo("zINSTREAM\0");
            assertThat(request.chunkLengths()).isEmpty();
            assertThat(request.terminated()).isTrue();
        }

        @Test
        void shortReadsOfTheContentAreSentAsTheyCome() throws Exception {
            InputStream trickling = new ByteArrayInputStream(CONTENT) {
                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return super.read(buffer, offset, Math.min(length, 5));
                }
            };
            clamd = FakeClamd.replying("stream: OK\0");

            scanner(TIMEOUT).findThreat(trickling);

            Request request = clamd.request();
            assertThat(request.chunkLengths()).containsExactly(5, 5, 5, 2);
            assertThat(request.content()).isEqualTo(CONTENT);
        }
    }

    @Nested
    class Replies {

        @Test
        void okMeansClean() {
            clamd = FakeClamd.replying("stream: OK\0");

            assertThat(scan(CONTENT)).isEmpty();
        }

        @Test
        void foundReturnsTheThreatName() {
            clamd = FakeClamd.replying("stream: Win.Test.EICAR_HDB-1 FOUND\0");

            assertThat(scan(CONTENT)).contains("Win.Test.EICAR_HDB-1");
        }

        @Test
        void threatNameMayContainSpaces() {
            clamd = FakeClamd.replying("stream: Some Threat Name FOUND\0");

            assertThat(scan(CONTENT)).contains("Some Threat Name");
        }

        @Test
        void replyEndedByTheConnectionInsteadOfANullByteIsRead() {
            clamd = FakeClamd.replying("stream: OK");

            assertThat(scan(CONTENT)).isEmpty();
        }

        @Test
        void surroundingWhitespaceIsIgnored() {
            clamd = FakeClamd.replying("stream: OK\n\0");

            assertThat(scan(CONTENT)).isEmpty();
        }

        @Test
        void bytesAfterTheNullTerminatorAreIgnored() {
            clamd = FakeClamd.replying("stream: OK\0stream: Evil FOUND\0");

            assertThat(scan(CONTENT)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "INSTREAM size limit exceeded. ERROR\0",
                "stream: lstat() failed: No such file or directory. ERROR\0",
                "UNKNOWN COMMAND\0",
                "Eicar FOUND\0",
                "stream: \0",
                "\0",
                ""
        })
        void anyOtherReplyMeansTheScanCouldNotRun(String reply) {
            clamd = FakeClamd.replying(reply);

            assertThatThrownBy(() -> scan(CONTENT))
                    .isInstanceOf(AntivirusUnavailableException.class)
                    .hasMessageStartingWith("The antivirus is temporarily unavailable: Unexpected clamd reply: ")
                    .hasNoCause();
        }

        @Test
        void errorReplyIsQuotedInTheMessage() {
            clamd = FakeClamd.replying("INSTREAM size limit exceeded. ERROR\0");

            assertThatThrownBy(() -> scan(CONTENT))
                    .hasMessage("The antivirus is temporarily unavailable: "
                            + "Unexpected clamd reply: INSTREAM size limit exceeded. ERROR");
        }
    }

    @Nested
    class Failures {

        @Test
        void refusedConnectionMeansTheScanCouldNotRun() throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closedPort = socket.getLocalPort();
            }
            ClamAvScanner scanner = new ClamAvScanner(
                    InetAddress.getLoopbackAddress().getHostAddress(), closedPort, TIMEOUT);

            assertThatThrownBy(() -> scanner.findThreat(new ByteArrayInputStream(CONTENT)))
                    .isInstanceOf(AntivirusUnavailableException.class)
                    .hasMessage("The antivirus is temporarily unavailable")
                    .hasCauseInstanceOf(ConnectException.class);
        }

        @Test
        void unknownHostMeansTheScanCouldNotRun() {
            ClamAvScanner scanner = new ClamAvScanner("clamd.invalid", 3310, TIMEOUT);

            assertThatThrownBy(() -> scanner.findThreat(new ByteArrayInputStream(CONTENT)))
                    .isInstanceOf(AntivirusUnavailableException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        void silentClamdTimesOut() {
            clamd = FakeClamd.silent();
            ClamAvScanner scanner = scanner(Duration.ofMillis(300));

            long start = System.nanoTime();
            assertThatThrownBy(() -> scanner.findThreat(new ByteArrayInputStream(CONTENT)))
                    .isInstanceOf(AntivirusUnavailableException.class)
                    // The read timeout and the scan deadline share the same delay: either may fire first
                    .hasCauseInstanceOf(IOException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT);
        }

        @Test
        void clamdThatStopsReadingTimesOut() {
            clamd = FakeClamd.neverReading();
            ClamAvScanner scanner = scanner(Duration.ofMillis(300));
            byte[] content = new byte[16 * 1024 * 1024];

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> scanner.findThreat(new ByteArrayInputStream(content)))
                            .isInstanceOf(AntivirusUnavailableException.class));
        }

        @Test
        void unreadableContentMeansTheScanCouldNotRun() {
            clamd = FakeClamd.replying("stream: OK\0");
            IOException failure = new IOException("disk");
            InputStream broken = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw failure;
                }
            };

            assertThatThrownBy(() -> scanner(TIMEOUT).findThreat(broken))
                    .isInstanceOf(AntivirusUnavailableException.class)
                    .hasCause(failure);
        }

        @Test
        void timeoutAboveIntegerRangeIsRejected() {
            assertThatThrownBy(() -> new ClamAvScanner("localhost", 3310, Duration.ofDays(30)))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    record Request(String command, List<Integer> chunkLengths, byte[] content, boolean terminated) {
    }

    /** One-connection clamd stand-in: records the INSTREAM request, then answers with raw bytes. */
    static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;

        private final CompletableFuture<Request> request = new CompletableFuture<>();

        private final CountDownLatch release = new CountDownLatch(1);

        private FakeClamd(Behaviour behaviour) {
            try {
                server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            Thread.ofVirtual().start(() -> serve(behaviour));
        }

        static FakeClamd replying(String reply) {
            return new FakeClamd(new Behaviour(true, reply.getBytes(StandardCharsets.US_ASCII)));
        }

        static FakeClamd silent() {
            return new FakeClamd(new Behaviour(true, null));
        }

        static FakeClamd neverReading() {
            return new FakeClamd(new Behaviour(false, null));
        }

        int port() {
            return server.getLocalPort();
        }

        Request request() throws ExecutionException, InterruptedException, TimeoutException {
            return request.get(5, TimeUnit.SECONDS);
        }

        private void serve(Behaviour behaviour) {
            try (Socket socket = server.accept()) {
                if (behaviour.readsRequest()) {
                    request.complete(readRequest(new DataInputStream(socket.getInputStream())));
                }

                if (behaviour.reply() == null) {
                    release.await();
                    return;
                }

                OutputStream out = socket.getOutputStream();
                out.write(behaviour.reply());
                out.flush();
            } catch (IOException | InterruptedException exception) {
                request.completeExceptionally(exception);
            }
        }

        private static Request readRequest(DataInputStream in) throws IOException {
            String command = new String(in.readNBytes("zINSTREAM\0".length()), StandardCharsets.US_ASCII);
            List<Integer> lengths = new ArrayList<>();
            ByteArrayOutputStream content = new ByteArrayOutputStream();

            while (true) {
                int length = in.readInt();
                if (length == 0) {
                    return new Request(command, lengths, content.toByteArray(), true);
                }
                lengths.add(length);
                content.write(in.readNBytes(length));
            }
        }

        @Override
        public void close() throws IOException {
            release.countDown();
            server.close();
        }

        private record Behaviour(boolean readsRequest, byte[] reply) {
        }
    }
}
