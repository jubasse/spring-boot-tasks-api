package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.notification.dtos.CreateWebhookEndpointDto;
import io.julienmetral.tasks.notification.dtos.UpdateWebhookEndpointDto;
import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDisabledReason;
import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.exceptions.WebhookDeliveryNotFoundException;
import io.julienmetral.tasks.notification.exceptions.WebhookEndpointNotFoundException;
import io.julienmetral.tasks.notification.exceptions.WebhookLimitReachedException;
import io.julienmetral.tasks.notification.repositories.WebhookDeliveryRepository;
import io.julienmetral.tasks.notification.repositories.WebhookEndpointRepository;
import io.julienmetral.tasks.notification.webhook.WebhookDeliveryService;
import io.julienmetral.tasks.notification.webhook.WebhookProperties;
import io.julienmetral.tasks.notification.webhook.WebhookSecrets;
import io.julienmetral.tasks.notification.webhook.WebhookTestResult;
import io.julienmetral.tasks.notification.webhook.WebhookUrlPolicy;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookEndpointService {

    public record CreatedWebhookEndpoint(WebhookEndpoint endpoint, String secret) {
    }

    public record RotatedWebhookSecret(String secret, Instant previousSecretExpiresAt) {
    }

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryService deliveryService;
    private final RateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookSecrets secrets;
    private final WebhookProperties properties;
    private final Clock clock;

    /** Returns the endpoint with its secret in clear, which no later read shows again. */
    @Transactional
    public CreatedWebhookEndpoint create(UUID userId, CreateWebhookEndpointDto dto) {
        // Locking the account row makes two concurrent creations count each other against the limit
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (endpointRepository.countByUserId(userId) >= properties.maxPerUser()) {
            throw new WebhookLimitReachedException(properties.maxPerUser());
        }

        urlPolicy.check(dto.url());

        Instant now = clock.instant();
        String secret = secrets.generate();

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUser(userProfileRepository.getReferenceById(userId));
        endpoint.setUrl(dto.url());
        endpoint.setSecret(secrets.encrypt(secret));
        endpoint.setEvents(EnumSet.copyOf(dto.events()));
        endpoint.setCreatedAt(now);
        endpoint.setUpdatedAt(now);

        return new CreatedWebhookEndpoint(endpointRepository.save(endpoint), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> findAll(UUID userId) {
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        return endpointRepository.findAllByUserIdOrderByCreatedAt(userId);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(UUID userId, UUID webhookId) {
        return find(userId, webhookId);
    }

    @Transactional
    public WebhookEndpoint update(UUID userId, UUID webhookId, UpdateWebhookEndpointDto dto) {
        WebhookEndpoint endpoint = find(userId, webhookId);

        if (!endpoint.getUrl().equals(dto.url())) {
            urlPolicy.check(dto.url());
            endpoint.setUrl(dto.url());
        }

        endpoint.setEvents(EnumSet.copyOf(dto.events()));

        Instant now = clock.instant();

        // Only a disabled endpoint is enabled again: enable() restarts the count of days without success
        if (dto.enabled()) {
            if (!endpoint.isEnabled()) {
                endpoint.enable();
            }
        } else {
            endpoint.disable(WebhookDisabledReason.OWNER, now);
        }

        endpoint.setUpdatedAt(now);

        return endpoint;
    }

    /** Deletes the endpoint with its deliveries, pending ones included. */
    @Transactional
    public void delete(UUID userId, UUID webhookId) {
        WebhookEndpoint endpoint = find(userId, webhookId);

        deliveryRepository.deleteAllByEndpointId(endpoint.getId());
        endpointRepository.delete(endpoint);
    }

    /**
     * Sends a test event to the endpoint and returns how the receiver answered; nothing is recorded. Rate limited per
     * owner, since each call is an HTTP request to a URL the user chose. Not transactional: the call can take seconds.
     */
    public WebhookTestResult sendTest(UUID userId, UUID webhookId) {
        WebhookEndpoint endpoint = find(userId, webhookId);

        rateLimiter.webhookTest(userId);

        return deliveryService.sendTest(endpoint);
    }

    /** Starts a delivery over with the full retry schedule, under the same {@code webhook-id}. */
    @Transactional
    public WebhookDelivery redeliver(UUID userId, UUID webhookId, UUID deliveryId) {
        WebhookEndpoint endpoint = find(userId, webhookId);
        WebhookDelivery delivery = deliveryRepository
                .findByIdAndEndpointId(deliveryId, endpoint.getId())
                .orElseThrow(() -> new WebhookDeliveryNotFoundException(deliveryId));

        deliveryService.requeue(delivery);

        return delivery;
    }

    @Transactional(readOnly = true)
    public Page<WebhookDelivery> findDeliveries(UUID userId, UUID webhookId, Pageable pageable) {
        return deliveryRepository.findAllByEndpointId(find(userId, webhookId).getId(), pageable);
    }

    /**
     * Replaces the signing secret. The previous one keeps signing deliveries, next to the new one, for
     * {@code webhooks.previous-secret-validity}, so the receiver can switch without losing any.
     */
    @Transactional
    public RotatedWebhookSecret rotateSecret(UUID userId, UUID webhookId) {
        WebhookEndpoint endpoint = find(userId, webhookId);
        Instant now = clock.instant();
        Instant previousSecretExpiresAt = now.plus(properties.previousSecretValidity());
        String secret = secrets.generate();

        endpoint.setPreviousSecret(endpoint.getSecret());
        endpoint.setPreviousSecretExpiresAt(previousSecretExpiresAt);
        endpoint.setSecret(secrets.encrypt(secret));
        endpoint.setUpdatedAt(now);

        return new RotatedWebhookSecret(secret, previousSecretExpiresAt);
    }

    // The account is read first: its endpoints stay in the database while it is soft-deleted, until the erasure
    private WebhookEndpoint find(UUID userId, UUID webhookId) {
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        return endpointRepository
                .findByIdAndUserId(webhookId, userId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(webhookId));
    }
}
