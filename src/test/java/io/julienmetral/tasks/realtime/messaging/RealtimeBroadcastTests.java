package io.julienmetral.tasks.realtime.messaging;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.realtime.RealtimeConfiguration;
import io.julienmetral.tasks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real-time broadcasts through the outbox and the real broker, and what this instance does when one arrives. */
@IntegrationTest
class RealtimeBroadcastTests {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private Outbox outbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void taskNotificationIsBroadcastThroughAnOutboxRowForTheExchangeWhichIsMarkedPublished() throws Exception {
        User assignee = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/tasks")
                        .with(as(createUser(UserRole.ADMIN), UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "RB-%s", "title": "Broadcast", "assignedTo": "%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), assignee.getId())))
                .andExpect(status().isCreated());

        OutboxMessage row = awaitPublished(onlyBroadcastRow(UserNotification.class, "recipientId", assignee));
        assertThat(row.getExchange()).isEqualTo("tasks.realtime");
        assertThat(row.getQueue()).isNull();
        assertThat(row.getPayload()).containsEntry("type", "task.assigned");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void accountChangeIsBroadcastThroughAnOutboxRowForTheExchangeWhichIsMarkedPublished() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/users/{id}/disable", user.getId())
                        .with(as(createUser(UserRole.ADMIN), UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        OutboxMessage row = awaitPublished(onlyBroadcastRow(AccountStatusChanged.class, "userId", user));
        assertThat(row.getExchange()).isEqualTo("tasks.realtime");
        assertThat(row.getQueue()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void accountChangeBroadcastByAnotherInstanceEvictsTheStatusCachedHere() throws Exception {
        User user = createUser(UserRole.USER);
        listTasks(user).andExpect(status().isOk());
        // A change this instance never saw: only the broadcast can evict its cached ACTIVE before the TTL
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", user.getId());
        listTasks(user).andExpect(status().isOk());
        assertThat(cachedStatusOf(user)).isEqualTo(UserStatus.ACTIVE);

        transactionTemplate.executeWithoutResult(transaction ->
                outbox.broadcast(RealtimeConfiguration.EXCHANGE, new AccountStatusChanged(user.getId())));

        await().atMost(PUBLISH_TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> cachedStatusOf(user) == null);
        listTasks(user).andExpect(status().isForbidden());
    }

    @Test
    void broadcastThatNoQueueReceivesIsMarkedPublishedWithoutARetry() {
        String exchange = "realtime-tests." + UUID.randomUUID();
        amqpAdmin.declareExchange(new FanoutExchange(exchange, false, false));

        try {
            transactionTemplate.executeWithoutResult(transaction ->
                    outbox.broadcast(exchange, new AccountStatusChanged(UUID.randomUUID())));

            List<UUID> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM outbox_messages WHERE exchange = ?", UUID.class, exchange);
            assertThat(ids).hasSize(1);
            OutboxMessage row = awaitPublished(ids.getFirst());
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getLastError()).isNull();
        } finally {
            amqpAdmin.deleteExchange(exchange);
        }
    }

    private UUID onlyBroadcastRow(Class<?> type, String payloadKey, User user) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT id FROM outbox_messages WHERE exchange = 'tasks.realtime' AND type = ? AND payload ->> ? = ?",
                UUID.class,
                type.getName(),
                payloadKey,
                user.getId().toString()
        );

        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    // The relay publishes on an async thread after commit, and sets published_at once the broker confirmed
    private OutboxMessage awaitPublished(UUID id) {
        return await().atMost(PUBLISH_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> outboxRepository.findById(id).orElseThrow(), row -> row.getPublishedAt() != null);
    }

    private UserStatus cachedStatusOf(User user) {
        Cache.ValueWrapper cached = cacheManager.getCache(UserStatusLookup.CACHE).get(user.getId());
        return cached == null ? null : (UserStatus) cached.get();
    }

    private ResultActions listTasks(User user) throws Exception {
        return mockMvc.perform(get("/api/v1/tasks").with(as(user, UserRole.USER)));
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail("realtime-broadcast-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Broadcast " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private static RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
