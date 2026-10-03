package io.julienmetral.tasks.notification.webhook;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

import java.net.ConnectException;
import java.net.URI;
import java.util.Map;

/**
 * The HTTP call of a delivery, implemented by Spring from this interface (HTTP service client, group
 * {@value WebhookConfiguration#CLIENT_GROUP}). The URL is an argument because every endpoint has its own.
 * <p>
 * The body is the exact bytes that were signed: an object serialized during the call could differ from them. Every
 * status comes back as a response, since the delivery records 4xx and 5xx rather than failing on them.
 */
public interface WebhookClient {

    /**
     * Retried once, quickly, only when no connection could be made: the request then never reached the receiver, so
     * sending it again cannot deliver it twice. Every other failure is left to the delivery's own schedule.
     */
    @Retryable(includes = {ConnectException.class, ConnectTimeoutException.class}, maxRetries = 1, delay = 200,
            jitter = 100)
    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(URI url, @RequestHeader Map<String, String> headers, @RequestBody byte[] body);
}
