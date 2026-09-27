package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.support.RepositoryTest;
import io.julienmetral.tasks.support.SqlStatementCounter;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

@RepositoryTest
class UserRepositoryTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void existsByEmailIncludingDeletedSeesASoftDeletedUserThatDerivedQueriesIgnore() {
        User user = persistUser(uniqueEmail());
        softDelete(user);

        assertThat(userRepository.findByEmailIgnoreCase(user.getEmail())).isEmpty();
        assertThat(userRepository.existsByEmailIncludingDeleted(user.getEmail())).isTrue();
    }

    @Test
    void existsByEmailIncludingDeletedIgnoresCase() {
        String email = "Mixed-" + UUID.randomUUID() + "@Example.com";
        persistUser(email);

        assertThat(userRepository.existsByEmailIncludingDeleted(email.toLowerCase())).isTrue();
        assertThat(userRepository.existsByEmailIncludingDeleted(uniqueEmail())).isFalse();
    }

    @Test
    void emailOfASoftDeletedUserStaysTaken() {
        User user = persistUser(uniqueEmail());
        softDelete(user);

        assertThatThrownBy(() -> userRepository.saveAndFlush(newUser(user.getEmail())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserKeepsTheAccountRowAndAProfileWithStatusDeleted() {
        User user = persistUser(uniqueEmail());
        UUID id = user.getId();

        softDelete(user);

        assertThat(userRepository.findById(id)).isEmpty();
        assertThat(userProfileRepository.findById(id))
                .get()
                .extracting(UserProfile::getStatus)
                .isEqualTo(UserStatus.DELETED);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, id)).isNotNull();
    }

    @Test
    void findByIdForUpdateSkipsASoftDeletedUser() {
        User deleted = persistUser(uniqueEmail());
        softDelete(deleted);
        User active = persistUser(uniqueEmail());
        entityManager.clear();

        assertThat(userRepository.findByIdForUpdate(deleted.getId())).isEmpty();
        assertThat(userRepository.findByIdForUpdate(active.getId())).isPresent();
    }

    @Test
    void findByIdForUpdateLocksTheRow() throws Exception {
        UUID id = persistUser(uniqueEmail()).getId();
        entityManager.clear();

        List<String> statements = SqlStatementCounter.statementsDuring(() -> userRepository.findByIdForUpdate(id));

        assertThat(statements).anySatisfy(sql -> assertThat(sql).containsPattern("(?i)for (no key )?update"));
    }

    @Test
    void findAccountStateByIdReadsWhetherTheAccountIsEnabledAndWhenItWasVerified() {
        Instant verifiedAt = Instant.parse("2026-01-01T00:00:00Z");
        User user = newUser(uniqueEmail());
        user.setEnabled(false);
        user.setEmailVerifiedAt(verifiedAt);
        UUID id = entityManager.persistAndFlush(user).getId();
        entityManager.clear();

        assertThat(userRepository.findAccountStateById(id)).contains(new AccountState(false, verifiedAt));
    }

    @Test
    void findAccountStateByIdSkipsASoftDeletedUserAndAnUnknownId() {
        User deleted = persistUser(uniqueEmail());
        softDelete(deleted);

        assertThat(userRepository.findAccountStateById(deleted.getId())).isEmpty();
        assertThat(userRepository.findAccountStateById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAccountStateByIdReadsTheAccountRowAlone() throws Exception {
        UUID id = persistUser(uniqueEmail()).getId();
        entityManager.clear();

        List<String> statements = SqlStatementCounter.statementsDuring(() -> userRepository.findAccountStateById(id));

        assertThat(statements)
                .singleElement(as(STRING))
                .startsWith("select ")
                .doesNotContain(" join ", "password_hash");
    }

    @Test
    void findWithProfileByIdLoadsTheProfileItsPhotosAndTheRoles() {
        User user = newUser(uniqueEmail());
        user.getRoles().add(UserRole.ADMIN);
        user.getProfile().setAvatar(persistMedia());
        user.getProfile().setPendingAvatar(persistMedia());
        UUID id = entityManager.persistAndFlush(user).getId();
        entityManager.clear();

        User loaded = userRepository.findWithProfileById(id).orElseThrow();

        PersistenceUnitUtil loadState = entityManager.getEntityManager()
                .getEntityManagerFactory()
                .getPersistenceUnitUtil();
        assertThat(loadState.isLoaded(loaded, "profile")).isTrue();
        assertThat(loadState.isLoaded(loaded.getProfile(), "avatar")).isTrue();
        assertThat(loadState.isLoaded(loaded.getProfile(), "pendingAvatar")).isTrue();
        // Warning: the roles are EAGER and not in the graph. A FETCH graph, instead of LOAD, left them unloaded.
        assertThat(loadState.isLoaded(loaded, "roles")).isTrue();
        assertThat(loaded.getRoles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void updatingAUserWritesOnlyTheColumnsItChanged() throws Exception {
        // Warning: rewriting the whole row from the loaded state once re-enabled an account that an admin disabled
        // while its photo was being processed (see @DynamicUpdate on User and UserProfile)
        UUID id = persistUser(uniqueEmail()).getId();
        entityManager.clear();
        User user = userRepository.findWithProfileById(id).orElseThrow();

        List<String> statements = SqlStatementCounter.statementsDuring(() -> {
            user.markActive(Instant.now());
            user.getProfile().setAvatar(persistMedia());
            entityManager.flush();
        });

        assertThat(statements)
                .filteredOn(sql -> sql.startsWith("update users "))
                .singleElement(as(STRING))
                .contains("last_active_at")
                .doesNotContain("enabled", "email", "password_hash");
        assertThat(statements)
                .filteredOn(sql -> sql.startsWith("update user_profiles "))
                .singleElement(as(STRING))
                .contains("avatar_media_id")
                .doesNotContain("status", "display_name");
    }

    private User persistUser(String email) {
        return entityManager.persistAndFlush(newUser(email));
    }

    private void softDelete(User user) {
        user.markDeleted();
        userRepository.delete(user);
        entityManager.flush();
        entityManager.clear();
    }

    private Media persistMedia() {
        Media media = new Media();
        media.setStorageKey("avatar/" + UUID.randomUUID());
        media.setUsage(MediaUsage.AVATAR);
        media.setOriginalFilename("photo.png");
        media.setContentType("image/png");
        media.setSizeBytes(1);
        media.setSha256("0".repeat(64));
        media.setCreatedAt(Instant.now());
        return entityManager.persist(media);
    }

    private static User newUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("!");
        user.setDisplayName("Repository user");
        user.setRoles(new HashSet<>(Set.of(UserRole.USER)));
        return user;
    }

    private static String uniqueEmail() {
        return "repository-" + UUID.randomUUID() + "@example.com";
    }
}
