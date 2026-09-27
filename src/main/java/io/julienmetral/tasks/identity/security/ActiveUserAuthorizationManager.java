package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Grants access only to users that are enabled, not deleted and have verified their email.
 * <p>
 * The status is checked on every request, through a short cache ({@link UserStatusLookup}), because access tokens are
 * stateless: a token issued before the account was disabled or deleted stays valid until it expires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveUserAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final CurrentUser currentUser;
    private final UserStatusLookup userStatusLookup;

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context
    ) {
        Optional<UUID> userId = currentUser.getId(authentication.get());

        if (userId.isEmpty()) {
            return new AuthorizationDecision(false);
        }

        UserStatus status = userStatusLookup.statusOf(userId.get());

        if (status != UserStatus.ACTIVE) {
            log.info("Task access denied to account {}, which is {}", userId.get(), status);
            return new AuthorizationDecision(false);
        }

        return new AuthorizationDecision(true);
    }
}
