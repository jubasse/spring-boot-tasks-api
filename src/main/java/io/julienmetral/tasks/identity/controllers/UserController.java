package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.dtos.CreateUserDto;
import io.julienmetral.tasks.identity.dtos.UpdateUserDto;
import io.julienmetral.tasks.identity.dtos.UserResponseDto;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.services.AvatarService;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.openapi.RateLimited;
import io.julienmetral.tasks.shared.security.AdminOnly;
import io.julienmetral.tasks.shared.security.AllowedRolesOrSelfOnly;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Accounts: sign-up, profile, profile photo, and the admin actions.")
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final MediaUrls mediaUrls;
    private final RateLimiter rateLimiter;

    @Operation(summary = "Sign up")
    @ResponseStatus(HttpStatus.CREATED)
    @DocumentedProblems(ProblemType.EMAIL_TAKEN)
    @RateLimited
    @PostMapping
    public ResponseEntity<UserResponseDto> signUp(
            @Valid @RequestBody CreateUserDto dto,
            HttpServletRequest request
    ) {
        rateLimiter.signUp(request.getRemoteAddr());

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

    @Operation(summary = "Get an account")
    @GetMapping("/{id}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> getUser(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                response(
                        userService.findById(id)
                )
        );
    }

    @Operation(summary = "Update an account")
    @PatchMapping("/{id}")
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> updateUser(
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

    @Operation(summary = "Enable an account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{id}/enable")
    @AdminOnly
    public ResponseEntity<Void> enableUser(
            @PathVariable UUID id
    ) {
        userService.enable(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @Operation(summary = "Disable an account and revoke its refresh tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{id}/disable")
    @AdminOnly
    public ResponseEntity<Void> disableUser(
            @PathVariable UUID id
    ) {
        userService.disable(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @Operation(summary = "Delete an account and revoke its refresh tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    @AdminOnly
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id
    ) {
        userService.delete(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    @Operation(summary = "Upload a profile photo, processed in the background")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @DocumentedProblems(ProblemType.INVALID_IMAGE)
    @PutMapping(path = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AllowedRolesOrSelfOnly(UserRole.ADMIN)
    public ResponseEntity<UserResponseDto> uploadAvatar(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity
                .accepted()
                .body(response(avatarService.update(id, file)));
    }

    @Operation(summary = "Remove the profile photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
