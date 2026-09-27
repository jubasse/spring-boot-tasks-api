package io.julienmetral.tasks.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Empties every cache of the context after each test, so a test starts without statuses or download URLs cached by
 * the previous one. Tests that change an account through SQL publish no {@code AccountStateChanged}, so its cached
 * status would otherwise outlive them.
 */
public final class ClearCachesAfterEach implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context)
                .getBeanProvider(CacheManager.class)
                .ifAvailable(ClearCachesAfterEach::invalidateAll);
    }

    private static void invalidateAll(CacheManager cacheManager) {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);

            if (cache != null) {
                cache.invalidate();
            }
        }
    }
}
