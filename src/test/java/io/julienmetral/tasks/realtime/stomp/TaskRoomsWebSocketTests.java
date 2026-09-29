package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.JwtProperties;
import io.julienmetral.tasks.realtime.messaging.TaskRoomEvent;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Type;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Task rooms over a real WebSocket, with tokens from the login endpoint: only a real server performs the handshake,
 * its origin check and the close codes. The configuration is the one of {@code MonitoringTests}, so these classes
 * share one cached context and its containers.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")
class TaskRoomsWebSocketTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String PASSWORD = "password";

    private static final String ROOMS = "/topic/tasks/";

    private static final WebSocketContainer WEB_SOCKET_CONTAINER = ContainerProvider.getWebSocketContainer();

    private record RoomMessage(String eventId, TaskRoomEvent event) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    private final List<Connection> connections = new ArrayList<>();

    @AfterEach
    void disconnect() {
        connections.forEach(connection -> connection.session.thenAccept(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        }));
    }

    @Test
    void connectWithoutATokenIsRefusedWithTheReasonThenClosed() throws Exception {
        Connection connection = connect(null);

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("A bearer access token is required");
        assertThat(connection.awaitClosed().getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
        assertThat(connection.session).failsWithin(TIMEOUT);
    }

    @Test
    void connectWithAnInvalidTokenIsRefusedWithTheReasonThenClosed() throws Exception {
        Connection connection = connect("not-a-token");

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Invalid access token");
        assertThat(connection.awaitClosed().getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    @Test
    void connectWithATokenSignedByAnotherKeyIsRefused() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();

        Connection connection = connect(token(otherEncoder, createUser(UserRole.USER), Instant.now().plus(Duration.ofMinutes(15))));

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Invalid access token");
    }

    @Test
    void connectWithAnExpiredTokenIsRefused() throws Exception {
        Connection connection = connect(token(jwtEncoder, createUser(UserRole.USER), Instant.now().minus(Duration.ofMinutes(5))));

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Invalid access token");
    }

    @Test
    void connectOfAnUnverifiedAccountIsRefusedWithTheReason() throws Exception {
        String token = login(createUnverifiedUser());

        Connection connection = connect(token);

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("The account is not active");
        assertThat(connection.session).failsWithin(TIMEOUT);
    }

    @Test
    void connectWithTheStillValidTokenOfADisabledAccountIsRefusedWithTheReason() throws Exception {
        User user = createUser(UserRole.USER);
        String token = login(user);
        disable(user);

        Connection connection = connect(token);

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("The account is not active");
    }

    @Test
    void activeAccountConnects() throws Exception {
        Connection connection = connect(login(createUser(UserRole.USER)));

        assertThat(connection.awaitConnected().isConnected()).isTrue();
    }

    @Test
    void handshakeIgnoresAnInvalidBearerTokenInItsOwnHeaders() throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setBearerAuth("not-a-token");

        Connection connection = connect(login(createUser(UserRole.USER)), handshake);

        assertThat(connection.awaitConnected().isConnected()).isTrue();
    }

    @Test
    void handshakeFromTheApisOwnOriginIsAccepted() throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:" + port);

        Connection connection = connect(login(createUser(UserRole.USER)), handshake);

        assertThat(connection.awaitConnected().isConnected()).isTrue();
    }

    @Test
    void handshakeFromAForeignOriginIsRefused() {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("https://evil.example.com");

        Connection connection = connect(null, handshake);

        assertThat(connection.session).failsWithin(TIMEOUT).withThrowableThat().withMessageContaining("403");
    }

    @Test
    void subscriptionToTheRoomOfAnUnknownTaskIsRefused() throws Exception {
        Connection connection = connect(login(createUser(UserRole.USER)));

        connection.awaitConnected().subscribe(ROOMS + UUID.randomUUID(), new IgnoredFrames());

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Access Denied");
        assertThat(connection.awaitClosed().getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    @Test
    void subscriptionToADestinationThatIsNotARoomIsRefused() throws Exception {
        Connection connection = connect(login(createUser(UserRole.USER)));

        connection.awaitConnected().subscribe("/topic/everything", new IgnoredFrames());

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Access Denied");
    }

    @Test
    void frameSentToARoomIsRefused() throws Exception {
        String adminToken = login(createUser(UserRole.ADMIN));
        UUID taskId = createTask(adminToken);
        Connection connection = connect(login(createUser(UserRole.USER)));

        connection.awaitConnected().send(ROOMS + taskId, "Anyone there?");

        assertThat(connection.awaitError().getFirst("message")).isEqualTo("Access Denied");
    }

    @Test
    void updateByAnotherUserReachesTheRoomWithItsHistoryRowItsActorAndTheBroadcastId() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String adminToken = login(admin);
        UUID taskId = createTask(adminToken);
        List<RoomMessage> room = subscribe(connect(login(createUser(UserRole.USER))), taskId);
        Instant before = Instant.now();

        patch("/api/v1/tasks/" + taskId, adminToken, Map.of("title", "Rotate the signing keys"));

        RoomMessage message = awaitEvent(room, "UPDATED");
        UUID historyRow = historyRowId(taskId, "UPDATED");
        assertThat(message.event().taskId()).isEqualTo(taskId);
        assertThat(message.event().eventId()).isEqualTo(historyRow);
        assertThat(message.event().actorId()).isEqualTo(admin.getId());
        assertThat(message.event().occurredAt()).isBetween(before, Instant.now());
        assertThat(message.eventId()).isEqualTo(broadcastIdOfHistoryRow(historyRow).toString());
    }

    @Test
    void commentByAnotherUserReachesTheRoom() throws Exception {
        String adminToken = login(createUser(UserRole.ADMIN));
        UUID taskId = createTask(adminToken);
        User commenter = createUser(UserRole.USER);
        List<RoomMessage> room = subscribe(connect(login(createUser(UserRole.USER))), taskId);

        post("/api/v1/tasks/" + taskId + "/comments", login(commenter), Map.of("body", "The old keys expire Friday"));

        RoomMessage message = awaitEvent(room, "COMMENT_ADDED");
        UUID historyRow = historyRowId(taskId, "COMMENT_ADDED");
        assertThat(message.event().taskId()).isEqualTo(taskId);
        assertThat(message.event().eventId()).isEqualTo(historyRow);
        assertThat(message.event().actorId()).isEqualTo(commenter.getId());
        assertThat(message.eventId()).isEqualTo(broadcastIdOfHistoryRow(historyRow).toString());
    }

    @Test
    void deletionByAnotherUserReachesTheRoomWithoutAHistoryRow() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String adminToken = login(admin);
        UUID taskId = createTask(adminToken);
        List<RoomMessage> room = subscribe(connect(login(createUser(UserRole.USER))), taskId);
        Instant before = Instant.now();

        delete("/api/v1/tasks/" + taskId, adminToken);

        RoomMessage message = awaitEvent(room, "DELETED");
        assertThat(message.event().taskId()).isEqualTo(taskId);
        assertThat(message.event().eventId()).isNull();
        assertThat(message.event().actorId()).isEqualTo(admin.getId());
        assertThat(message.event().occurredAt()).isBetween(before, Instant.now());
        assertThat(message.eventId()).isEqualTo(broadcastIdOfDeletion(taskId).toString());
    }

    @Test
    void roomReceivesOnlyTheEventsOfItsTask() throws Exception {
        String adminToken = login(createUser(UserRole.ADMIN));
        UUID taskId = createTask(adminToken);
        UUID otherTaskId = createTask(adminToken);
        List<RoomMessage> room = subscribe(connect(login(createUser(UserRole.USER))), taskId);
        patch("/api/v1/tasks/" + otherTaskId, adminToken, Map.of("title", "Another task changed"));
        // Confirmed by the broker before the next change exists: the single consumer hands it out first
        awaitPublished(broadcastIdOfHistoryRow(historyRowId(otherTaskId, "UPDATED")));

        patch("/api/v1/tasks/" + taskId, adminToken, Map.of("title", "This task changed"));

        awaitEvent(room, "UPDATED");
        assertThat(room).extracting(message -> message.event().taskId()).containsOnly(taskId);
    }

    @Test
    void disablingTheAccountClosesItsSessionAsAPolicyViolation() throws Exception {
        User user = createUser(UserRole.USER);
        Connection connection = connect(login(user));
        connection.awaitConnected();

        disable(user);

        assertThat(connection.awaitClosed()).isEqualTo(new CloseStatus(1008, "Account not active"));
    }

    @Test
    void sessionIsClosedAsAPolicyViolationWhenItsTokenExpires() throws Exception {
        Connection connection = connect(token(jwtEncoder, createUser(UserRole.USER), Instant.now().plusSeconds(2)));
        connection.awaitConnected();

        assertThat(connection.awaitClosed()).isEqualTo(new CloseStatus(1008, "Access token expired"));
    }

    private Connection connect(String accessToken) {
        return connect(accessToken, new WebSocketHttpHeaders());
    }

    private Connection connect(String accessToken, WebSocketHttpHeaders handshake) {
        Connection connection = new Connection();
        WebSocketStompClient client = new WebSocketStompClient(new CloseRecordingWebSocketClient(connection.closed));
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        StompHeaders connectHeaders = new StompHeaders();
        if (accessToken != null) {
            connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }

        connection.session = client.connectAsync(
                URI.create("ws://localhost:" + port + StompConfiguration.ENDPOINT), handshake, connectHeaders, connection);
        connections.add(connection);

        return connection;
    }

    private List<RoomMessage> subscribe(Connection connection, UUID taskId) throws Exception {
        List<RoomMessage> room = new CopyOnWriteArrayList<>();

        connection.awaitConnected().subscribe(ROOMS + taskId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return TaskRoomEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                room.add(new RoomMessage(headers.getFirst("event-id"), (TaskRoomEvent) payload));
            }
        });
        awaitSubscribed(ROOMS + taskId);

        return room;
    }

    // STOMP confirms no SUBSCRIBE: the broker's registry shows when it applies, before which an event would be missed
    private void awaitSubscribed(String destination) {
        SimpMessageHeaderAccessor probe = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        probe.setDestination(destination);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], probe.getMessageHeaders());

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> !broker.getSubscriptionRegistry().findSubscriptions(message).isEmpty());
    }

    // The broadcast of the task's creation may still be on its way to the room when the test subscribes
    private static RoomMessage awaitEvent(List<RoomMessage> room, String type) {
        return await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> room.stream().filter(message -> message.event().type().equals(type)).findFirst(),
                        Optional::isPresent)
                .orElseThrow();
    }

    private UUID historyRowId(UUID taskId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM task_events WHERE task_id = ? AND type = ?", UUID.class, taskId, type);
    }

    private UUID broadcastIdOfHistoryRow(UUID historyRowId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_messages WHERE exchange = 'tasks.realtime' AND type = ? AND payload ->> 'eventId' = ?",
                UUID.class, TaskRoomEvent.class.getName(), historyRowId.toString());
    }

    private UUID broadcastIdOfDeletion(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_messages WHERE exchange = 'tasks.realtime' AND type = ?"
                        + " AND payload ->> 'taskId' = ? AND payload ->> 'type' = 'DELETED'",
                UUID.class, TaskRoomEvent.class.getName(), taskId.toString());
    }

    private void awaitPublished(UUID outboxRowId) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT published_at IS NOT NULL FROM outbox_messages WHERE id = ?",
                        Boolean.class, outboxRowId)));
    }

    private User createUser(UserRole role) {
        return saveUser(Instant.now(), EnumSet.of(UserRole.USER, role));
    }

    private User createUnverifiedUser() {
        return saveUser(null, EnumSet.of(UserRole.USER));
    }

    private User saveUser(Instant emailVerifiedAt, EnumSet<UserRole> roles) {
        User user = new User();

        user.setEmail("task-rooms-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEmailVerifiedAt(emailVerifiedAt);
        user.setDisplayName("Task rooms " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(roles);

        return userRepository.saveAndFlush(user);
    }

    private String login(User user) {
        return post("/api/v1/auth/login", null, Map.of("email", user.getEmail(), "password", PASSWORD))
                .path("accessToken").asString();
    }

    // Signed as JwtService signs the application's tokens, with the expiry of the test
    private String token(JwtEncoder encoder, User user, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getEmail())
                .issuedAt(expiresAt.minus(jwtProperties.ttl()))
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("uid", user.getId().toString())
                .claim("roles", List.of("ROLE_USER"))
                .build();

        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private void disable(User user) {
        post("/api/v1/users/" + user.getId() + "/disable", login(createUser(UserRole.ADMIN)), Map.of());
    }

    private UUID createTask(String token) {
        String reference = "ROOM-" + UUID.randomUUID().toString().substring(0, 8);

        return UUID.fromString(post("/api/v1/tasks", token, Map.of("reference", reference, "title", "Rotate the keys"))
                .path("id").asString());
    }

    private JsonNode post(String path, String token, Object body) {
        String response = api().post().uri(path)
                .headers(headers -> bearer(headers, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);

        return jsonMapper.readTree(response == null ? "{}" : response);
    }

    private void patch(String path, String token, Object body) {
        api().patch().uri(path)
                .headers(headers -> bearer(headers, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
    }

    private void delete(String path, String token) {
        api().delete().uri(path).headers(headers -> bearer(headers, token)).retrieve().toBodilessEntity();
    }

    private RestClient api() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void bearer(HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    /** One client connection: its STOMP session once connected, the ERROR frame it got, and how it was closed. */
    private static final class Connection extends StompSessionHandlerAdapter {

        private final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        private CompletableFuture<StompSession> session;

        // Frames of a subscription go to its own handler: only an ERROR frame reaches the session's
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            error.complete(headers);
        }

        StompSession awaitConnected() throws Exception {
            return session.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        StompHeaders awaitError() throws Exception {
            return error.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        CloseStatus awaitClosed() throws Exception {
            return closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class IgnoredFrames implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
        }
    }

    // The STOMP client reports a lost connection without its close code
    private static final class CloseRecordingWebSocketClient extends StandardWebSocketClient {

        private final CompletableFuture<CloseStatus> closed;

        CloseRecordingWebSocketClient(CompletableFuture<CloseStatus> closed) {
            super(WEB_SOCKET_CONTAINER);
            this.closed = closed;
        }

        @Override
        protected CompletableFuture<WebSocketSession> executeInternal(
                WebSocketHandler handler,
                HttpHeaders headers,
                URI uri,
                List<String> subProtocols,
                List<WebSocketExtension> extensions,
                Map<String, Object> attributes
        ) {
            WebSocketHandler recording = new WebSocketHandlerDecorator(handler) {
                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                    closed.complete(status);
                    super.afterConnectionClosed(session, status);
                }
            };

            return super.executeInternal(recording, headers, uri, subProtocols, extensions, attributes);
        }
    }
}
