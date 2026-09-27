package io.julienmetral.tasks.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.services.MediaService;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The application caches, in memory with Caffeine, each with its own settings on Spring Boot's cache manager. Their
 * names are also listed in {@code spring.cache.cache-names}: a mistyped name then fails instead of silently creating
 * a cache without limit or metrics. Statistics are recorded for the {@code cache.*} metrics.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(StatusCacheProperties.class)
public class CacheConfiguration {

    static final long MAXIMUM_DOWNLOAD_URLS = 10_000;

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> applicationCaches(
            StatusCacheProperties statusCache,
            StorageProperties storage,
            Clock clock
    ) {
        return cacheManager -> {
            cacheManager.setAllowNullValues(false);

            // Expires after write, never after access: a client calling in a loop with a disabled account's token
            // would otherwise keep its ACTIVE entry alive until the token expires
            cacheManager.registerCustomCache(UserStatusLookup.CACHE, Caffeine.newBuilder()
                    .expireAfterWrite(statusCache.ttl())
                    .maximumSize(statusCache.maximumSize())
                    .recordStats()
                    .build());

            cacheManager.registerCustomCache(MediaService.DOWNLOAD_URLS, Caffeine.newBuilder()
                    .expireAfter(Expiry.creating((Object key, Object download) -> reuseWindow(
                            ((MediaDownload) download).expiresAt(),
                            storage.presignedUrlTtl(),
                            clock
                    )))
                    .maximumSize(MAXIMUM_DOWNLOAD_URLS)
                    .recordStats()
                    .build());
        };
    }

    /**
     * How long a presigned URL is handed out again: at most half its validity, and never so late that it has less
     * than half its validity left when served, including when temporary credentials cut it short.
     */
    static Duration reuseWindow(Instant expiresAt, Duration validity, Clock clock) {
        Duration half = validity.dividedBy(2);
        Duration usable = Duration.between(clock.instant(), expiresAt).minus(half);

        if (usable.isNegative()) {
            return Duration.ZERO;
        }
        return usable.compareTo(half) < 0 ? usable : half;
    }
}
