package io.julienmetral.tasks.realtime.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.TestClock;
import io.julienmetral.tasks.task.events.TaskAssigned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification streams end to end: a change commits its broadcast through the outbox, RabbitMQ copies it to this
 * instance's queue, and the listener hands it to the recipient's stream, read here while the response stays open.
 */
@IntegrationTest
class NotificationStreamApiTests {

    private static final String STREAM = "/api/v1/notifications/stream";

    private static final String TASKS = "/api/v1/tasks";

    private static final String TITLE = "Renew the TLS certificates";

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    private record SseEvent(String id, String name, String data) {
    }

    private record CreatedTask(UUID id, String reference) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TestClock clock;

    private final List<MvcResult> openStreams = new ArrayList<>();

    // As the client would by leaving: the stream is released and its writer stops
    @AfterEach
    void disconnectTheStreams() {
        openStreams.forEach(NotificationStreamApiTests::disconnect);
    }

    @Test
    void assignmentByAnotherUserReachesTheAssigneesStreamAsTheWebhookPayloadUnderTheOutboxRowId() throws Exception {
        clock.set(Instant.parse("2026-06-01T10:00:00Z"));
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        MvcResult stream = openStream(assignee);

        CreatedTask task = createTask(admin, assignee);

        SseEvent event = awaitEvents(stream, 1).getFirst();
        assertThat(event.id()).isEqualTo(broadcastRowIdFor(assignee).toString());
        assertThat(event.name()).isEqualTo("task.assigned");
        JsonNode data = jsonMapper.readTree(event.data());
        assertThat(data.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(data).isEqualTo(jsonMapper.valueToTree(Map.of(
                "type", "task.assigned",
                "timestamp", "2026-06-01T10:00:00Z",
                "data", Map.of(
                        "task", Map.of("id", task.id().toString(), "reference", task.reference(), "title", TITLE),
                        "actor", Map.of("id", admin.getId().toString(), "displayName", admin.getDisplayName())
                )
        )));
        assertThat(data.get("data").propertyNames()).containsExactly("task", "actor");
    }

    @Test
    void streamStartsWithAReconnectionDelay() throws Exception {
        MvcResult stream = openStream(createUser(UserRole.USER));

        await().atMost(DELIVERY_TIMEOUT).until(() -> content(stream).startsWith("retry:"));
    }

    @Test
    void actorDoesNotReceiveTheirOwnAction() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        MvcResult stream = openStream(admin);
        CreatedTask own = createTask(admin, admin);

        CreatedTask assignedByAnother = createTask(createUser(UserRole.ADMIN), admin);

        SseEvent event = awaitEvents(stream, 1).getFirst();
        assertThat(event.data()).contains(assignedByAnother.reference()).doesNotContain(own.reference());
        assertThat(broadcastRowIdsFor(admin)).hasSize(1);
    }

    @Test
    void notificationOfARolledBackChangeIsNeverBroadcast() throws Exception {
        User actor = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        MvcResult stream = openStream(assignee);
        String rolledBack = "RB-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(transaction -> {
            eventPublisher.publishEvent(new TaskAssigned(UUID.randomUUID(), rolledBack, TITLE, assignee.getId(),
                    actor.getId()));
            transaction.setRollbackOnly();
        });

        CreatedTask committed = createTask(actor, assignee);

        assertThat(awaitEvents(stream, 1).getFirst().data()).contains(committed.reference());
        assertThat(content(stream)).doesNotContain(rolledBack);
        assertThat(broadcastRowIdsFor(assignee)).hasSize(1);
    }

    @Test
    void disablingTheAccountClosesItsStreamWhichEndsNormallyAndCannotBeReopened() throws Exception {
        User user = createUser(UserRole.USER);
        MvcResult stream = openStream(user);

        mockMvc.perform(post("/api/v1/users/{id}/disable", user.getId()).with(as(createUser(UserRole.ADMIN), UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        stream.getAsyncResult(DELIVERY_TIMEOUT.toMillis());
        // The dispatch that ends the response belongs to a request authorized when it started
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        mockMvc.perform(streamRequest(user)).andExpect(status().isForbidden());
    }

    @Test
    void reconnectingWithTheLastEventIdReplaysTheNotificationMissedMeanwhile() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        MvcResult leaving = openStream(assignee);
        MvcResult staying = openStream(assignee);
        createTask(admin, assignee);
        SseEvent received = awaitEvents(leaving, 1).getFirst();
        disconnect(leaving);

        CreatedTask missed = createTask(admin, assignee);
        // Once the stream still open has it, this instance holds it for replay
        awaitEvents(staying, 2);

        MvcResult reconnected = openStream(assignee, received.id());

        List<SseEvent> replayed = awaitEvents(reconnected, 1);
        assertThat(replayed).hasSize(1);
        assertThat(replayed.getFirst().data()).contains(missed.reference());
        assertThat(replayed.getFirst().id()).isNotEqualTo(received.id());
    }

    @Test
    void reconnectingWithAnUnknownLastEventIdGetsAResyncEvent() throws Exception {
        MvcResult stream = openStream(createUser(UserRole.USER), UUID.randomUUID().toString());

        SseEvent event = awaitEvents(stream, 1).getFirst();
        assertThat(event.name()).isEqualTo("resync");
        assertThat(event.id()).isNull();
        assertThat(event.data()).isEqualTo("{\"type\":\"resync\"}");
    }

    private MvcResult openStream(User user) throws Exception {
        return openStream(user, null);
    }

    private MvcResult openStream(User user, String lastEventId) throws Exception {
        MockHttpServletRequestBuilder builder = streamRequest(user);

        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }

        MvcResult result = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        openStreams.add(result);
        return result;
    }

    private MockHttpServletRequestBuilder streamRequest(User user) {
        return get(STREAM)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(jwt()
                        .jwt(token -> token
                                .claim("uid", user.getId().toString())
                                .expiresAt(clock.instant().plus(Duration.ofMinutes(15))))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static void disconnect(MvcResult stream) {
        MockHttpServletRequest request = stream.getRequest();

        if (request.isAsyncStarted()) {
            request.getAsyncContext().complete();
        }
    }

    private static List<SseEvent> awaitEvents(MvcResult stream, int count) {
        return await().atMost(DELIVERY_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> events(stream), events -> events.size() >= count);
    }

    // The events whose block is complete: a read can land between the writes of one event
    private static List<SseEvent> events(MvcResult stream) {
        String content = content(stream);
        int end = content.lastIndexOf("\n\n");
        List<SseEvent> events = new ArrayList<>();

        for (String block : (end < 0 ? "" : content.substring(0, end)).split("\n\n")) {
            String id = null;
            String name = null;
            String data = null;

            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) {
                    id = line.substring("id:".length());
                } else if (line.startsWith("event:")) {
                    name = line.substring("event:".length());
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                }
            }

            if (name != null) {
                events.add(new SseEvent(id, name, data));
            }
        }

        return events;
    }

    private static String content(MvcResult stream) {
        try {
            return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private List<UUID> broadcastRowIdsFor(User recipient) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM outbox_messages WHERE exchange = 'tasks.realtime' AND payload ->> 'recipientId' = ?",
                UUID.class,
                recipient.getId().toString()
        );
    }

    private UUID broadcastRowIdFor(User recipient) {
        List<UUID> ids = broadcastRowIdsFor(recipient);
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private CreatedTask createTask(User creator, User assignee) throws Exception {
        String reference = "RT-" + UUID.randomUUID().toString().substring(0, 8);

        String created = mockMvc.perform(post(TASKS)
                        .with(as(creator, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "%s", "assignedTo": "%s"}
                                """.formatted(reference, TITLE, assignee.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return new CreatedTask(UUID.fromString(jsonMapper.readTree(created).get("id").asString()), reference);
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail("notification-stream-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Streamer " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private static RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
