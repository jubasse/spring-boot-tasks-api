package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.services.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(
            @Valid @RequestBody LoginRequestDto dto
    ) {
        return ResponseEntity.ok(
                authService.login(dto)
        );
    }
}