package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.notification.dtos.NotificationSettingsDto;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.shared.security.AllowedRolesOrSelfOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users/{id}/notification-settings")
@RequiredArgsConstructor
@Tag(name = "Notification settings", description = "Which task emails an account receives.")
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;

    @Operation(summary = "Get the notification settings")
    @GetMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<NotificationSettingsDto> getNotificationSettings(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                new NotificationSettingsDto(settingsService.get(id))
        );
    }

    @Operation(summary = "Replace the notification settings")
    @PutMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<NotificationSettingsDto> updateNotificationSettings(
            @PathVariable UUID id,
            @Valid @RequestBody NotificationSettingsDto dto
    ) {
        return ResponseEntity.ok(
                new NotificationSettingsDto(settingsService.update(id, dto))
        );
    }
}
