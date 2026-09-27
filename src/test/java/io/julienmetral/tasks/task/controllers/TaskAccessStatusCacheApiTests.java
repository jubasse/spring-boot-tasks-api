package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.events.AccountStateChanged;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskAccessStatusCacheApiTests extends AbstractUserStateTaskApiTests {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Mailpit mailpit;

    @Test
    void cachedActiveAccountIsRefusedOnItsNextRequestOnceDisabled() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isOk());
        assertThat(cachedStatusOf(user.getId())).isEqualTo(UserStatus.ACTIVE);

        disableThroughApi(user);

        listTasks(asUser(user)).andExpect(status().isForbidden());
    }

    @Test
    void cachedActiveAccountIsRefusedOnItsNextRequestOnceDeleted() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isOk());
        assertThat(cachedStatusOf(user.getId())).isEqualTo(UserStatus.ACTIVE);

        deleteThroughApi(user);

        listTasks(asUser(user)).andExpect(status().isForbidden());
    }

    @Test
    void disabledAccountIsLetInOnItsNextRequestOnceEnabled() throws Exception {
        User user = createUser(UserRole.USER);
        disableThroughApi(user);
        listTasks(asUser(user)).andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/users/{id}/enable", user.getId()).with(asAdmin(createUser(UserRole.ADMIN))))
                .andExpect(status().isNoContent());

        listTasks(asUser(user)).andExpect(status().isOk());
    }

    @Test
    void unverifiedAccountIsLetInOnItsNextRequestOnceItVerifiesItsEmail() throws Exception {
        String email = uniqueEmail();
        RequestPostProcessor account = asUserWithId(signUp(email));
        listTasks(account).andExpect(status().isForbidden());
        String token = mailpit.latestVerificationTokenFor(email);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\"}".formatted(token)))
                .andExpect(status().isNoContent());

        listTasks(account).andExpect(status().isOk());
    }

    @Test
    void unverifiedAccountIsLetInOnItsNextRequestOnceAPasswordResetVerifiesItsEmail() throws Exception {
        User user = createUnverifiedUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(user.getEmail())))
                .andExpect(status().isAccepted());
        String token = Mailpit.extractToken(mailpit.latestTextTo(user.getEmail()));

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\", \"newPassword\": \"brand-new-password\"}".formatted(token)))
                .andExpect(status().isNoContent());

        listTasks(asUser(user)).andExpect(status().isOk());
    }

    @Test
    void refusedStatusIsCachedUntilTheAccountChanges() throws Exception {
        User user = createUnverifiedUser(UserRole.USER);

        listTasks(asUser(user)).andExpect(status().isForbidden());

        assertThat(cachedStatusOf(user.getId())).isEqualTo(UserStatus.UNVERIFIED);
    }

    @Test
    void changePublishedOutsideATransactionStillEvictsTheStatus() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", user.getId());
        // A change that publishes nothing keeps its cached ACTIVE until the TTL
        listTasks(asUser(user)).andExpect(status().isOk());
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        eventPublisher.publishEvent(new AccountStateChanged(user.getId()));

        listTasks(asUser(user)).andExpect(status().isForbidden());
    }

    @Test
    void statusIsEvictedOnlyOnceTheChangeCommits() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isOk());

        transactionTemplate.executeWithoutResult(transaction -> {
            eventPublisher.publishEvent(new AccountStateChanged(user.getId()));

            assertThat(cachedStatusOf(user.getId())).isEqualTo(UserStatus.ACTIVE);
        });

        assertThat(cachedStatusOf(user.getId())).isNull();
    }

    @Test
    void rolledBackChangeKeepsTheCachedStatus() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(asUser(user)).andExpect(status().isOk());

        transactionTemplate.executeWithoutResult(transaction -> {
            eventPublisher.publishEvent(new AccountStateChanged(user.getId()));
            transaction.setRollbackOnly();
        });

        assertThat(cachedStatusOf(user.getId())).isEqualTo(UserStatus.ACTIVE);
    }

    private ResultActions listTasks(RequestPostProcessor caller) throws Exception {
        return mockMvc.perform(get(TASKS).with(caller));
    }

    private UserStatus cachedStatusOf(UUID userId) {
        Cache cache = cacheManager.getCache(UserStatusLookup.CACHE);

        assertThat(cache).isNotNull();
        return cache.get(userId, UserStatus.class);
    }

    private UUID signUp(String email) throws Exception {
        String location = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "displayName": "Status cache"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private static RequestPostProcessor asUserWithId(UUID userId) {
        return jwt()
                .jwt(token -> token.claim("uid", userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + UserRole.USER.name()));
    }

    private static String uniqueEmail() {
        return "status-cache-" + UUID.randomUUID() + "@example.com";
    }
}
