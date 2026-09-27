package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The status that task access depends on, cached per account for {@code identity.status-cache.ttl}. A change to the
 * account evicts it once committed ({@link UserStatusCacheEviction}); the TTL bounds how long a missed eviction, for
 * example on another instance, lets a disabled or deleted account keep access.
 * <p>
 * Only ACTIVE is cached, so an account that becomes active is let in at once. A failed read propagates and caches
 * nothing: the request is refused rather than served from a stale status.
 */
@Component
@RequiredArgsConstructor
public class UserStatusLookup {

    public static final String CACHE = "userStatus";

    private final UserRepository userRepository;

    @Cacheable(cacheNames = CACHE, unless = "#result != T(io.julienmetral.tasks.identity.entities.UserStatus).ACTIVE")
    public UserStatus statusOf(UUID userId) {
        return userRepository.findAccountStateById(userId)
                .map(AccountState::status)
                .orElse(UserStatus.DELETED);
    }
}
