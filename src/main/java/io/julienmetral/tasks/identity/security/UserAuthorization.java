package io.julienmetral.tasks.identity.security;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("userAuthorization")
@RequiredArgsConstructor
public class UserAuthorization {

    private final CurrentUser currentUser;

    public boolean currentUserIsSelf(UUID userId, Authentication authentication) {
        return currentUser.getId(authentication).map(userId::equals).orElse(false);
    }
}
