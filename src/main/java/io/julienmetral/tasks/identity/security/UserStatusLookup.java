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
 * example on another instance, leaves a stale status. A failed read propagates and caches nothing: the request is
 * refused rather than served from a stale status.
 */
@Component
@RequiredArgsConstructor
public class UserStatusLookup {

    public static final String CACHE = "userStatus";

    private final UserRepository userRepository;

    // sync: Caffeine loads under the key's lock, so an eviction that arrives during the load waits for it, then removes
    // what it stored. Without it, a request that read ACTIVE just before a disable committed stored it after the
    // eviction, and the disabled account kept access until the TTL. Spring forbids unless with sync, so every status
    // is cached, which is why every change to an account evicts.
    @Cacheable(cacheNames = CACHE, sync = true)
    public UserStatus statusOf(UUID userId) {
        return userRepository.findAccountStateById(userId)
                .map(AccountState::status)
                .orElse(UserStatus.DELETED);
    }
}
