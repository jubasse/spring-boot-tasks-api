package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.notification.dtos.CreateWebhookEndpointDto;
import io.julienmetral.tasks.notification.dtos.NewWebhookEndpointDto;
import io.julienmetral.tasks.notification.dtos.UpdateWebhookEndpointDto;
import io.julienmetral.tasks.notification.dtos.WebhookEndpointResponseDto;
import io.julienmetral.tasks.notification.dtos.WebhookSecretDto;
import io.julienmetral.tasks.notification.services.WebhookEndpointService;
import io.julienmetral.tasks.notification.services.WebhookEndpointService.CreatedWebhookEndpoint;
import io.julienmetral.tasks.notification.services.WebhookEndpointService.RotatedWebhookSecret;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.security.AllowedRolesOrSelfOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{id}/webhooks")
@RequiredArgsConstructor
@Tag(
        name = "Webhooks",
        description = "HTTPS endpoints of an account that receive its task notifications, signed with the Standard "
                + "Webhooks scheme."
)
public class WebhookEndpointController {

    private final WebhookEndpointService webhookService;

    @Operation(summary = "Declare a webhook", description = "The response carries the signing secret, shown only once.")
    @ResponseStatus(HttpStatus.CREATED)
    @DocumentedProblems({ProblemType.WEBHOOK_URL_NOT_ALLOWED, ProblemType.WEBHOOK_LIMIT_REACHED})
    @PostMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<NewWebhookEndpointDto> createWebhook(
            @PathVariable UUID id,
            @Valid @RequestBody CreateWebhookEndpointDto dto
    ) {
        CreatedWebhookEndpoint created = webhookService.create(id, dto);

        return ResponseEntity
                .created(URI.create("/api/v1/users/" + id + "/webhooks/" + created.endpoint().getId()))
                .body(new NewWebhookEndpointDto(created.endpoint(), created.secret()));
    }

    @Operation(summary = "List the webhooks of an account, oldest first")
    @GetMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public List<WebhookEndpointResponseDto> listWebhooks(@PathVariable UUID id) {
        return webhookService.findAll(id).stream().map(WebhookEndpointResponseDto::new).toList();
    }

    @Operation(summary = "Get a webhook")
    @GetMapping("/{webhookId}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public WebhookEndpointResponseDto getWebhook(@PathVariable UUID id, @PathVariable UUID webhookId) {
        return new WebhookEndpointResponseDto(webhookService.get(id, webhookId));
    }

    @Operation(summary = "Replace a webhook's URL, events and state")
    @DocumentedProblems(ProblemType.WEBHOOK_URL_NOT_ALLOWED)
    @PutMapping("/{webhookId}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public WebhookEndpointResponseDto updateWebhook(
            @PathVariable UUID id,
            @PathVariable UUID webhookId,
            @Valid @RequestBody UpdateWebhookEndpointDto dto
    ) {
        return new WebhookEndpointResponseDto(webhookService.update(id, webhookId, dto));
    }

    @Operation(summary = "Delete a webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{webhookId}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public void deleteWebhook(@PathVariable UUID id, @PathVariable UUID webhookId) {
        webhookService.delete(id, webhookId);
    }

    @Operation(
            summary = "Replace a webhook's signing secret",
            description = "The previous secret keeps signing deliveries, next to the new one, until "
                    + "previousSecretExpiresAt."
    )
    @PostMapping("/{webhookId}/secret")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public WebhookSecretDto rotateWebhookSecret(@PathVariable UUID id, @PathVariable UUID webhookId) {
        RotatedWebhookSecret rotated = webhookService.rotateSecret(id, webhookId);

        return new WebhookSecretDto(rotated.secret(), rotated.previousSecretExpiresAt());
    }
}
