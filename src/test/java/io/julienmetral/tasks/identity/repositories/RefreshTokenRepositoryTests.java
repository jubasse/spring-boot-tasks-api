package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.RefreshToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.support.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class RefreshTokenRepositoryTests {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private static final Instant EARLIER = NOW.minus(Duration.ofHours(1));

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void revokeAllForUserRevokesTheActiveTokensOfASoftDeletedUser() {
        User user = persistUser();
        RefreshToken first = persistToken(user, UUID.randomUUID(), null);
        RefreshToken second = persistToken(user, UUID.randomUUID(), null);
        RefreshToken alreadyRevoked = persistToken(user, UUID.randomUUID(), EARLIER);
        user.markDeleted();
        userRepository.delete(user);
        entityManager.flush();
        entityManager.clear();

        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), NOW);

        assertThat(revoked).isEqualTo(2);
        assertThat(revokedAt(first)).isEqualTo(NOW);
        assertThat(revokedAt(second)).isEqualTo(NOW);
        assertThat(revokedAt(alreadyRevoked)).isEqualTo(EARLIER);
    }

    @Test
    void revokeAllForUserLeavesTheTokensOfOtherUsers() {
        User user = persistUser();
        persistToken(user, UUID.randomUUID(), null);
        RefreshToken ofAnotherUser = persistToken(persistUser(), UUID.randomUUID(), null);

        refreshTokenRepository.revokeAllForUser(user.getId(), NOW);

        assertThat(revokedAt(ofAnotherUser)).isNull();
    }

    @Test
    void revokeFamilyLeavesTheOtherSessionsOfTheUser() {
        User user = persistUser();
        UUID family = UUID.randomUUID();
        RefreshToken rotated = persistToken(user, family, EARLIER);
        RefreshToken current = persistToken(user, family, null);
        RefreshToken otherSession = persistToken(user, UUID.randomUUID(), null);

        int revoked = refreshTokenRepository.revokeFamily(family, NOW);

        assertThat(revoked).isOne();
        assertThat(revokedAt(current)).isEqualTo(NOW);
        assertThat(revokedAt(rotated)).isEqualTo(EARLIER);
        assertThat(revokedAt(otherSession)).isNull();
    }

    private User persistUser() {
        User user = new User();
        user.setEmail("tokens-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("!");
        user.setDisplayName("Token holder");
        user.setRoles(new HashSet<>(Set.of(UserRole.USER)));
        return entityManager.persistAndFlush(user);
    }

    private RefreshToken persistToken(User user, UUID family, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user.getProfile());
        token.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        token.setFamilyId(family);
        token.setCreatedAt(EARLIER.minus(Duration.ofDays(1)));
        token.setExpiresAt(NOW.plus(Duration.ofDays(30)));
        token.setRevokedAt(revokedAt);
        return entityManager.persistAndFlush(token);
    }

    // The bulk updates bypass the persistence context: read the row again
    private Instant revokedAt(RefreshToken token) {
        entityManager.clear();
        return entityManager.find(RefreshToken.class, token.getId()).getRevokedAt();
    }
}
