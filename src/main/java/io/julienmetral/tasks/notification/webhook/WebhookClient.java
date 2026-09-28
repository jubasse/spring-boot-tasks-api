package io.julienmetral.tasks.notification.webhook;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

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

    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(URI url, @RequestHeader Map<String, String> headers, @RequestBody byte[] body);
}
