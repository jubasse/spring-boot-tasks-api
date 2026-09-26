package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.dtos.CreateUserDto;
import io.julienmetral.tasks.identity.dtos.UpdateUserDto;
import io.julienmetral.tasks.identity.dtos.UserResponseDto;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.services.AvatarService;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.shared.security.AdminOnly;
import io.julienmetral.tasks.shared.security.AllowedRolesOrSelfOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final MediaUrls mediaUrls;

    @PostMapping
    public ResponseEntity<UserResponseDto> create(
            @Valid @RequestBody CreateUserDto dto
    ) {
        User user = userService.create(
                dto.email(),
                dto.password(),
                dto.displayName()
        );

        return ResponseEntity
                .created(
                        URI.create(
                                "/api/v1/users/" + user.getId()
                        )
                )
                .body(
                        response(user)
                );
    }

    @GetMapping("/{id}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> findById(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                response(
                        userService.findById(id)
                )
        );
    }

    @PatchMapping("/{id}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserDto dto
    ) {
        User user = userService.updateProfile(
                id,
                dto.displayName()
        );

        return ResponseEntity.ok(
                response(user)
        );
    }

    @PostMapping("/{id}/enable")
    @AdminOnly
    public ResponseEntity<Void> enable(
            @PathVariable UUID id
    ) {
        userService.enable(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @PostMapping("/{id}/disable")
    @AdminOnly
    public ResponseEntity<Void> disable(
            @PathVariable UUID id
    ) {
        userService.disable(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    public ResponseEntity<Void> delete(
            @PathVariable UUID id
    ) {
        userService.delete(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @PutMapping(path = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> updateAvatar(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                response(avatarService.update(id, file))
        );
    }

    @DeleteMapping("/{id}/avatar")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<Void> removeAvatar(
            @PathVariable UUID id
    ) {
        avatarService.remove(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    private UserResponseDto response(User entity) {
        return new UserResponseDto(entity, mediaUrls);
    }
}
