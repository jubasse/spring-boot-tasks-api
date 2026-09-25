package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.notification.dtos.NotificationSettingsDto;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.shared.security.AllowedRolesOrSelfOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{id}/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;

    @GetMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<NotificationSettingsDto> get(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                new NotificationSettingsDto(settingsService.get(id))
        );
    }

    @PutMapping
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<NotificationSettingsDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody NotificationSettingsDto dto
    ) {
        return ResponseEntity.ok(
                new NotificationSettingsDto(settingsService.update(id, dto))
        );
    }
}
