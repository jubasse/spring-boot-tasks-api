package io.julienmetral.tasks.realtime.sse;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The client of a stream, over a mock response: what Spring MVC does with the emitter a controller returns, so that
 * from then on its events reach the response and its completion ends the request.
 */
final class SseClient {

    private final MockHttpServletRequest request;
    private final MockHttpServletResponse response;

    private SseClient(MockHttpServletRequest request, MockHttpServletResponse response) {
        this.request = request;
        this.response = response;
    }

    static SseClient connect(SseEmitter emitter) {
        return connect(emitter, new MockHttpServletResponse());
    }

    static SseClient connect(SseEmitter emitter, MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.setAsyncSupported(true);
        StandardServletAsyncWebRequest asyncWebRequest = new StandardServletAsyncWebRequest(request, response);
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncWebRequest);

        try {
            new ResponseBodyEmitterReturnValueHandler(List.of(new StringHttpMessageConverter(StandardCharsets.UTF_8)))
                    .handleReturnValue(emitter, returnTypeOfOpen(), new ModelAndViewContainer(), asyncWebRequest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }

        return new SseClient(request, response);
    }

    String content() {
        try {
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** The events, retry and comments written so far, one block each, without their closing blank line. */
    List<String> blocks() {
        return Arrays.stream(content().split("\n\n")).filter(block -> !block.isEmpty()).toList();
    }

    /** Whether the response was completed, which ends the request. */
    boolean closed() {
        return WebAsyncUtils.getAsyncManager(request).hasConcurrentResult();
    }

    /** As the servlet container does when the client goes away. */
    void leave() {
        request.getAsyncContext().complete();
    }

    private static MethodParameter returnTypeOfOpen() throws NoSuchMethodException {
        return new MethodParameter(NotificationStreams.class.getMethod("open", UUID.class, Instant.class, String.class), -1);
    }

    /**
     * A client that stops reading: once {@link #stall() stalled}, every write blocks until {@link #resume()}, as a
     * write to a full socket buffer does under Tomcat.
     */
    static final class StallingResponse extends MockHttpServletResponse {

        private final CountDownLatch resumed = new CountDownLatch(1);
        private final CountDownLatch writeBlocked = new CountDownLatch(1);
        private volatile boolean stalled;

        void stall() {
            stalled = true;
        }

        void resume() {
            resumed.countDown();
        }

        boolean awaitBlockedWrite(Duration timeout) throws InterruptedException {
            return writeBlocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            ServletOutputStream delegate = super.getOutputStream();

            return new ServletOutputStream() {

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }

                @Override
                public void write(int b) throws IOException {
                    if (stalled) {
                        writeBlocked.countDown();
                        awaitResumed();
                    }
                    delegate.write(b);
                }
            };
        }

        private void awaitResumed() {
            try {
                resumed.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
