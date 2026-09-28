package io.julienmetral.tasks.notification.webhook;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDeliveryStatus;
import io.julienmetral.tasks.notification.entities.WebhookDisabledReason;
import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.repositories.WebhookDeliveryQueries;
import io.julienmetral.tasks.notification.repositories.WebhookDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends webhook deliveries and records how each attempt ended. An attempt reads the delivery, calls the receiver
 * outside any transaction, then records the result: a slow receiver never holds a database connection.
 */
@Service
public class WebhookDeliveryService {

    /**
     * The wait before each retry, after the Standard Webhooks schedule cut at about one day, since task notifications
     * lose their value quickly. A receiver down for a deployment or an afternoon still gets the event.
     */
    static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(5),
            Duration.ofHours(10)
    );

    // A Retry-After above this is capped: past it, the event has lost its value
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(10);

    private static final String DESTINATION_NOT_ALLOWED = "DestinationNotAllowed";

    private static final String TEST_EVENT = "webhook.test";

    private record Attempt(UUID deliveryId, URI url, byte[] body, List<String> secrets) {
    }

    private record Outcome(Integer statusCode, String error, Duration retryAfter) {

        // Not HttpStatus.valueOf, which throws on codes Spring has no constant for, such as 522 behind Cloudflare
        boolean delivered() {
            return statusCode != null && statusCode / 100 == 2;
        }
    }

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryQueries deliveryQueries;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final JsonMapper jsonMapper;
    private final WebhookClient client;
    private final WebhookSigner signer;
    private final WebhookSecrets secrets;
    private final WebhookProperties properties;
    private final Outbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public WebhookDeliveryService(
            WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryQueries deliveryQueries,
            UserRepository userRepository,
            MailService mailService,
            JsonMapper jsonMapper,
            WebhookClient client,
            WebhookSigner signer,
            WebhookSecrets secrets,
            WebhookProperties properties,
            Outbox outbox,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryQueries = deliveryQueries;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.jsonMapper = jsonMapper;
        this.client = client;
        this.signer = signer;
        this.secrets = secrets;
        this.properties = properties;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates one delivery per endpoint and queues their first attempt, in the caller's transaction: nothing is sent
     * for a change that rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(List<WebhookEndpoint> endpoints, WebhookEvent event, String payload) {
        Instant now = clock.instant();

        for (WebhookEndpoint endpoint : endpoints) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpoint(endpoint);
            delivery.setEvent(event);
            delivery.setPayload(payload);
            delivery.setStatus(WebhookDeliveryStatus.PENDING);
            delivery.setNextAttemptAt(now.plus(properties.deliveryLease()));
            delivery.setCreatedAt(now);

            UUID deliveryId = deliveryRepository.save(delivery).getId();

            outbox.enqueue(WebhookQueues.DELIVER, new WebhookDeliveryRequested(deliveryId));
        }
    }

    /** Queues the pending deliveries whose retry is due; each one is leased so no other poll queues it again. */
    @Transactional
    public int enqueueDue() {
        Instant now = clock.instant();
        List<UUID> due = deliveryQueries.claimDue(now, now.plus(properties.deliveryLease()),
                properties.retryBatchSize());

        due.forEach(id -> outbox.enqueue(WebhookQueues.DELIVER, new WebhookDeliveryRequested(id)));

        return due.size();
    }

    /**
     * Starts a delivery over, whatever its state: the full retry schedule applies again, under the same
     * {@code webhook-id}, so a receiver that already processed it can drop it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeue(WebhookDelivery delivery) {
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(clock.instant().plus(properties.deliveryLease()));
        delivery.setDeliveredAt(null);

        outbox.enqueue(WebhookQueues.DELIVER, new WebhookDeliveryRequested(delivery.getId()));
    }

    /**
     * Sends a {@code webhook.test} event to the endpoint at once, signed like a delivery, whether the endpoint is
     * paused or not, and records nothing. Runs outside any transaction.
     */
    public WebhookTestResult sendTest(WebhookEndpoint endpoint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TEST_EVENT);
        payload.put("timestamp", clock.instant().toString());
        payload.put("data", Map.of("webhookId", endpoint.getId()));

        long start = System.nanoTime();
        Outcome outcome = send(new Attempt(
                UUID.randomUUID(),
                URI.create(endpoint.getUrl()),
                jsonMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8),
                signingSecrets(endpoint)
        ));

        return new WebhookTestResult(
                outcome.delivered(),
                outcome.statusCode(),
                outcome.error(),
                Duration.ofNanos(System.nanoTime() - start).toMillis()
        );
    }

    /** Deletes the deliveries older than {@code webhooks.delivery-retention}, whatever their state. */
    @Transactional
    public int purge() {
        return deliveryQueries.deleteCreatedBefore(clock.instant().minus(properties.deliveryRetention()));
    }

    /** One attempt of a pending delivery; a delivery already finished, by a duplicate message for example, is left. */
    public void deliver(UUID deliveryId) {
        Optional<Attempt> attempt = transactions.execute(status -> prepare(deliveryId));

        if (attempt != null && attempt.isPresent()) {
            Outcome outcome = send(attempt.get());

            transactions.executeWithoutResult(status -> record(deliveryId, outcome));
        }
    }

    private Optional<Attempt> prepare(UUID deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findWithEndpointById(deliveryId).orElse(null);

        if (delivery == null || delivery.getStatus() != WebhookDeliveryStatus.PENDING) {
            return Optional.empty();
        }

        WebhookEndpoint endpoint = delivery.getEndpoint();

        if (!endpoint.isEnabled()) {
            fail(delivery, "EndpointDisabled");
            return Optional.empty();
        }

        // Retries run for about a day: an account disabled or deleted meanwhile stops receiving, as for new events
        if (!isActive(endpoint.getUser().getId())) {
            fail(delivery, "AccountNotActive");
            return Optional.empty();
        }

        return Optional.of(new Attempt(
                deliveryId,
                URI.create(endpoint.getUrl()),
                delivery.getPayload().getBytes(StandardCharsets.UTF_8),
                signingSecrets(endpoint)
        ));
    }

    private List<String> signingSecrets(WebhookEndpoint endpoint) {
        List<String> signingSecrets = new ArrayList<>();
        signingSecrets.add(secrets.decrypt(endpoint.getSecret()));

        if (endpoint.getPreviousSecret() != null && endpoint.getPreviousSecretExpiresAt().isAfter(clock.instant())) {
            signingSecrets.add(secrets.decrypt(endpoint.getPreviousSecret()));
        }

        return signingSecrets;
    }

    private Outcome send(Attempt attempt) {
        var headers = signer.headers(attempt.deliveryId().toString(), clock.instant(), attempt.body(),
                attempt.secrets());

        try {
            ResponseEntity<Void> response = client.send(attempt.url(), headers, attempt.body());

            return new Outcome(response.getStatusCode().value(), null, retryAfter(response));
        } catch (FilteredHostException refused) {
            return new Outcome(null, DESTINATION_NOT_ALLOWED, null);
        } catch (ResourceAccessException unreachable) {
            return new Outcome(null, unreachable.getCause() instanceof SocketTimeoutException
                    ? "Timeout"
                    : "ConnectionFailed", null);
        } catch (RestClientException invalid) {
            return new Outcome(null, "InvalidResponse", null);
        }
    }

    private void record(UUID deliveryId, Outcome outcome) {
        // Locked: two copies of one message could otherwise record their attempts over each other
        WebhookDelivery delivery = deliveryRepository.findForUpdateById(deliveryId).orElse(null);

        if (delivery == null || delivery.getStatus() != WebhookDeliveryStatus.PENDING) {
            return;
        }

        Instant now = clock.instant();
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(now);
        delivery.setLastStatusCode(outcome.statusCode());
        delivery.setLastError(outcome.error());

        if (outcome.delivered()) {
            delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
            delivery.setDeliveredAt(now);
            delivery.setNextAttemptAt(null);
            delivery.getEndpoint().setFailingSince(null);
            count("delivered");
            return;
        }

        if (outcome.statusCode() != null && outcome.statusCode() == HttpStatus.GONE.value()) {
            delivery.getEndpoint().disable(WebhookDisabledReason.GONE, now);
            fail(delivery, null);
            return;
        }

        trackFailure(delivery.getEndpoint(), now);

        if (DESTINATION_NOT_ALLOWED.equals(outcome.error())) {
            // Not a passing failure: retrying would only probe the address again
            fail(delivery, null);
        } else if (delivery.getAttempts() > RETRY_DELAYS.size()) {
            fail(delivery, outcome.error());
        } else {
            delivery.setNextAttemptAt(now.plus(nextDelay(delivery.getAttempts(), outcome.retryAfter())));
            count("retried");
        }
    }

    /**
     * Disables an endpoint whose every attempt failed for {@code webhooks.disable-after}, and emails its owner: without
     * it, a dead receiver would be called for every task event, forever.
     */
    private void trackFailure(WebhookEndpoint endpoint, Instant now) {
        if (endpoint.getFailingSince() == null) {
            endpoint.setFailingSince(now);
        } else if (endpoint.isEnabled()
                && !now.isBefore(endpoint.getFailingSince().plus(properties.disableAfter()))) {
            endpoint.disable(WebhookDisabledReason.FAILING, now);
            notifyDisabled(endpoint);
        }
    }

    // The host only: the rest of a URL can hold a secret, the path of a Slack webhook for one
    private void notifyDisabled(WebhookEndpoint endpoint) {
        userRepository.findById(endpoint.getUser().getId()).ifPresent(owner -> mailService.send(new MailMessage(
                owner.getEmail(),
                "Your webhook was disabled",
                """
                        Hello %s,

                        Your webhook to %s failed every attempt for %d days, so it was disabled: task notifications \
                        are no longer sent to it.

                        Once the receiver works again, turn the webhook back on in your webhook settings.
                        """.formatted(owner.getDisplayName(), URI.create(endpoint.getUrl()).getHost(),
                        properties.disableAfter().toDays())
        )));
    }

    private void fail(WebhookDelivery delivery, String error) {
        delivery.setStatus(WebhookDeliveryStatus.FAILED);
        delivery.setNextAttemptAt(null);

        if (error != null) {
            delivery.setLastError(error);
        }

        count("failed");
    }

    // Up to 20% either way, so the retries of many deliveries that failed together do not arrive together
    private static Duration nextDelay(int attempts, Duration retryAfter) {
        Duration delay = RETRY_DELAYS.get(attempts - 1);
        double factor = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        Duration jittered = Duration.ofMillis((long) (delay.toMillis() * factor));

        return retryAfter != null && retryAfter.compareTo(jittered) > 0 ? retryAfter : jittered;
    }

    // Retry-After holds either seconds or an HTTP date
    private Duration retryAfter(ResponseEntity<Void> response) {
        String value = response.getHeaders().getFirst("Retry-After");

        if (value == null) {
            return null;
        }

        Duration requested;

        try {
            requested = Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException notSeconds) {
            try {
                requested = Duration.between(clock.instant(),
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException neither) {
                return null;
            }
        }

        if (requested.isNegative()) {
            return null;
        }

        return requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested;
    }

    private boolean isActive(UUID userId) {
        return userRepository.findById(userId).map(UserStatus::of).orElse(null) == UserStatus.ACTIVE;
    }

    private void count(String outcome) {
        meterRegistry.counter("webhook.deliveries", "outcome", outcome).increment();
    }
}
