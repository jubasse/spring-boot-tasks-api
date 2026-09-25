package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Grants access only to users that are enabled, not deleted and have verified their email.
 * <p>
 * The user is reloaded on every request because access tokens are stateless: a token issued before the account was
 * disabled or deleted stays valid until it expires.
 */
@Component
@RequiredArgsConstructor
public class ActiveUserAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context
    ) {
        boolean active = currentUser
                .getId(authentication.get())
                .flatMap(userRepository::findById)
                .map(UserStatus::of)
                .filter(UserStatus.ACTIVE::equals)
                .isPresent();

        return new AuthorizationDecision(active);
    }
}
