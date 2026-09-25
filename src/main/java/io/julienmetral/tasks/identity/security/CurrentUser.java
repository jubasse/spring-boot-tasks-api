package io.julienmetral.tasks.identity.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUser {

    public Optional<UUID> getId() {
        return getId(
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
        );
    }

    public Optional<UUID> getId(
            Authentication authentication
    ) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Optional.empty();
        }

        String uid = jwtAuthentication
                .getToken()
                .getClaimAsString("uid");

        if (uid == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    UUID.fromString(uid)
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}