package io.julienmetral.tasks.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.services.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.DURATION;

class CacheConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Duration VALIDITY = Duration.ofMinutes(10);

    // A second of slack for the time Caffeine's own ticker runs between the write and the read of an expiry
    private static final Duration TICKER_SLACK = Duration.ofSeconds(1);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
            .withUserConfiguration(CacheConfiguration.class, StorageConfiguration.class)
            .withBean(Clock.class, () -> CLOCK);

    private final ApplicationContextRunner configured = runner.withPropertyValues(
            "spring.cache.type=caffeine",
            "spring.cache.cache-names=userStatus,mediaDownloads",
            "identity.status-cache.ttl=45s",
            "identity.status-cache.maximum-size=123",
            "storage.driver=rustfs",
            "storage.bucket=tasks-media",
            "storage.presigned-url-ttl=10m",
            "media.avatar-max-size=5MB",
            "media.attachment-max-size=25MB"
    );

    @Test
    void statusCacheExpiresAfterWriteWithTheConfiguredTtlAndHoldsTheConfiguredSize() {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            Policy<Object, Object> policy = nativeCache(context, UserStatusLookup.CACHE).policy();

            assertThat(policy.expireAfterWrite()).get()
                    .extracting(Policy.FixedExpiration::getExpiresAfter)
                    .isEqualTo(Duration.ofSeconds(45));
            assertThat(policy.eviction()).get()
                    .extracting(Policy.Eviction::getMaximum)
                    .isEqualTo(123L);
            assertThat(policy.isRecordingStats()).isTrue();
        });
    }

    @Test
    void statusCacheNeverExpiresAfterAccessNorRefreshes() {
        configured.run(context -> {
            Policy<Object, Object> policy = nativeCache(context, UserStatusLookup.CACHE).policy();

            assertThat(policy.expireAfterAccess()).isEmpty();
            assertThat(policy.refreshAfterWrite()).isEmpty();
            assertThat(policy.expireVariably()).isEmpty();
        });
    }

    @Test
    void downloadCacheKeepsEachUrlForItsReuseWindow() {
        configured.run(context -> {
            CaffeineCache cache = cache(context, MediaService.DOWNLOAD_URLS);
            UUID fresh = UUID.randomUUID();
            UUID cutShort = UUID.randomUUID();

            cache.put(fresh, download(NOW.plus(VALIDITY)));
            cache.put(cutShort, download(NOW.plus(Duration.ofMinutes(8))));

            Policy.VarExpiration<Object, Object> expiry = cache.getNativeCache().policy().expireVariably().orElseThrow();
            assertThat(expiry.getExpiresAfter(fresh)).get(DURATION)
                    .isBetween(Duration.ofMinutes(5).minus(TICKER_SLACK), Duration.ofMinutes(5));
            assertThat(expiry.getExpiresAfter(cutShort)).get(DURATION)
                    .isBetween(Duration.ofMinutes(3).minus(TICKER_SLACK), Duration.ofMinutes(3));
        });
    }

    @Test
    void downloadCacheDropsAUrlWithLessThanHalfItsValidityLeft() {
        configured.run(context -> {
            CaffeineCache cache = cache(context, MediaService.DOWNLOAD_URLS);
            UUID mediaId = UUID.randomUUID();

            cache.put(mediaId, download(NOW.plus(Duration.ofMinutes(4))));

            assertThat(cache.get(mediaId)).isNull();
        });
    }

    @Test
    void downloadCacheIsBoundedAndRecordsStatistics() {
        configured.run(context -> {
            Policy<Object, Object> policy = nativeCache(context, MediaService.DOWNLOAD_URLS).policy();

            assertThat(policy.eviction()).get()
                    .extracting(Policy.Eviction::getMaximum)
                    .isEqualTo(CacheConfiguration.MAXIMUM_DOWNLOAD_URLS);
            assertThat(policy.expireAfterAccess()).isEmpty();
            assertThat(policy.isRecordingStats()).isTrue();
        });
    }

    @Test
    void ttlOfOneSecondAndOfOneMinuteAreAccepted() {
        configured.withPropertyValues("identity.status-cache.ttl=1s")
                .run(context -> assertThat(context).hasNotFailed());
        configured.withPropertyValues("identity.status-cache.ttl=1m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void zeroTtlFailsStartup() {
        configured.withPropertyValues("identity.status-cache.ttl=0s")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("DurationMin.identity.status-cache.ttl"));
    }

    @Test
    void ttlAboveOneMinuteFailsStartup() {
        configured.withPropertyValues("identity.status-cache.ttl=61s")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("DurationMax.identity.status-cache.ttl"));
    }

    @Test
    void missingTtlFailsStartup() {
        configured.withPropertyValues("identity.status-cache.ttl=")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("NotNull.identity.status-cache.ttl"));
    }

    @Test
    void zeroMaximumSizeFailsStartup() {
        configured.withPropertyValues("identity.status-cache.maximum-size=0")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Positive.identity.status-cache.maximumSize"));
    }

    @Test
    void applicationYamlShipsTheDocumentedDefaults() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StatusCacheProperties properties = context.getBean(StatusCacheProperties.class);
                    assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.maximumSize()).isEqualTo(10_000);
                    assertThat(nativeCache(context, UserStatusLookup.CACHE).policy().expireAfterWrite()).get()
                            .extracting(Policy.FixedExpiration::getExpiresAfter)
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void mistypedCacheNameGetsNoCache() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addLast(mainApplicationYaml()))
                .run(context -> {
                    CacheManager cacheManager = context.getBean(CacheManager.class);
                    assertThat(cacheManager.getCacheNames())
                            .containsExactlyInAnyOrder(UserStatusLookup.CACHE, MediaService.DOWNLOAD_URLS);
                    assertThat(cacheManager.getCache("userStatuses")).isNull();
                });
    }

    @Test
    void freshUrlIsReusedForHalfItsValidity() {
        assertThat(CacheConfiguration.reuseWindow(NOW.plus(VALIDITY), VALIDITY, CLOCK))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void credentialsEndingSoonShortenTheReuseWindow() {
        assertThat(CacheConfiguration.reuseWindow(NOW.plus(Duration.ofMinutes(8)), VALIDITY, CLOCK))
                .isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void urlWithHalfItsValidityLeftIsNotReused() {
        assertThat(CacheConfiguration.reuseWindow(NOW.plus(Duration.ofMinutes(5)), VALIDITY, CLOCK))
                .isZero();
    }

    @Test
    void urlWithLessThanHalfItsValidityLeftIsNotReused() {
        assertThat(CacheConfiguration.reuseWindow(NOW.plus(Duration.ofMinutes(4)), VALIDITY, CLOCK))
                .isZero();
        assertThat(CacheConfiguration.reuseWindow(NOW.minusSeconds(1), VALIDITY, CLOCK))
                .isZero();
    }

    private static CaffeineCache cache(AssertableApplicationContext context, String name) {
        return (CaffeineCache) context.getBean(CacheManager.class).getCache(name);
    }

    private static Cache<Object, Object> nativeCache(AssertableApplicationContext context, String name) {
        return cache(context, name).getNativeCache();
    }

    private static MediaDownload download(Instant expiresAt) throws Exception {
        URL url = URI.create("http://storage.example/tasks-media/avatar/" + UUID.randomUUID()).toURL();

        return new MediaDownload(url, expiresAt);
    }

    private static PropertySource<?> mainApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("main application.yaml", new ClassPathResource("application.yaml"))
                    .getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
