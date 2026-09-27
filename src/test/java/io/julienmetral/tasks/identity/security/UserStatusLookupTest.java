package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
class UserStatusLookupTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Configuration
    @EnableCaching
    @Import({UserStatusLookup.class, UserStatusCacheEviction.class})
    static class CachingConfiguration {

        @Bean
        CacheManager cacheManager() {
            CaffeineCacheManager cacheManager = new CaffeineCacheManager(UserStatusLookup.CACHE);
            cacheManager.setAllowNullValues(false);
            return cacheManager;
        }
    }

    @MockitoBean
    private UserRepository userRepository;

    @Autowired
    private UserStatusLookup lookup;

    @Autowired
    private UserStatusCacheEviction eviction;

    @Autowired
    private CacheManager cacheManager;

    // The context, and so the cache, is shared by the tests: each one uses accounts of its own
    private final UUID userId = UUID.randomUUID();

    @Test
    void activeStatusIsReadOnceThenServedFromTheCache() {
        stubState(true, VERIFIED_AT);

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.ACTIVE);
        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.ACTIVE);

        verify(userRepository, times(1)).findAccountStateById(userId);
        assertThat(cache().get(userId, UserStatus.class)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void unverifiedStatusIsNotCached() {
        stubState(true, null);

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.UNVERIFIED);
        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.UNVERIFIED);

        verify(userRepository, times(2)).findAccountStateById(userId);
        assertThat(cache().get(userId)).isNull();
    }

    @Test
    void disabledStatusIsNotCached() {
        stubState(false, VERIFIED_AT);

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.DISABLED);

        assertThat(cache().get(userId)).isNull();
    }

    @Test
    void accountThatBecomesActiveIsSeenOnTheNextLookup() {
        stubState(true, null);
        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.UNVERIFIED);

        stubState(true, VERIFIED_AT);

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void accountThatIsNotFoundIsDeletedAndNotCached() {
        when(userRepository.findAccountStateById(userId)).thenReturn(Optional.empty());

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.DELETED);

        assertThat(cache().get(userId)).isNull();
    }

    @Test
    void failingRepositoryPropagatesAndCachesNothing() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database down");
        when(userRepository.findAccountStateById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> lookup.statusOf(userId)).isSameAs(failure);

        assertThat(cache().get(userId)).isNull();
    }

    @Test
    void evictionRemovesOnlyTheChangedAccount() {
        UUID otherUserId = UUID.randomUUID();
        stubState(true, VERIFIED_AT);
        when(userRepository.findAccountStateById(otherUserId))
                .thenReturn(Optional.of(new AccountState(true, VERIFIED_AT)));
        lookup.statusOf(userId);
        lookup.statusOf(otherUserId);

        eviction.evict(new AccountStateChanged(userId));

        assertThat(cache().get(userId)).isNull();
        assertThat(cache().get(otherUserId, UserStatus.class)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void evictedStatusIsReadAgain() {
        stubState(true, VERIFIED_AT);
        lookup.statusOf(userId);
        stubState(false, VERIFIED_AT);

        eviction.evict(new AccountStateChanged(userId));

        assertThat(lookup.statusOf(userId)).isEqualTo(UserStatus.DISABLED);
    }

    private void stubState(boolean enabled, Instant emailVerifiedAt) {
        when(userRepository.findAccountStateById(userId))
                .thenReturn(Optional.of(new AccountState(enabled, emailVerifiedAt)));
    }

    private Cache cache() {
        return cacheManager.getCache(UserStatusLookup.CACHE);
    }
}
