package io.julienmetral.tasks.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application caches, in memory with Caffeine, each with its own settings on Spring Boot's cache manager. Their
 * names are also listed in {@code spring.cache.cache-names}: a mistyped name then fails instead of silently creating
 * a cache without limit or metrics. Statistics are recorded for the {@code cache.*} metrics.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(StatusCacheProperties.class)
public class CacheConfiguration {

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> applicationCaches(StatusCacheProperties statusCache) {
        return cacheManager -> {
            cacheManager.setAllowNullValues(false);

            // Expires after write, never after access: a client calling in a loop with a disabled account's token
            // would otherwise keep its ACTIVE entry alive until the token expires
            cacheManager.registerCustomCache(UserStatusLookup.CACHE, Caffeine.newBuilder()
                    .expireAfterWrite(statusCache.ttl())
                    .maximumSize(statusCache.maximumSize())
                    .recordStats()
                    .build());
        };
    }
}
