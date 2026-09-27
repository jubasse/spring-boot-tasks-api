package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.events.AccountStateChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class UserStatusCacheEviction {

    private final CacheManager cacheManager;

    // After the commit: evicted before it, the entry could be reloaded with the old status by a concurrent request.
    // A load already running when this evicts is covered by the synchronized load of UserStatusLookup. Without
    // fallbackExecution, a change published outside a transaction would never evict. Spring only logs an exception
    // thrown here, so the TTL stays the bound.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void evict(AccountStateChanged event) {
        Cache cache = cacheManager.getCache(UserStatusLookup.CACHE);

        if (cache != null) {
            cache.evictIfPresent(event.userId());
        }
    }
}
