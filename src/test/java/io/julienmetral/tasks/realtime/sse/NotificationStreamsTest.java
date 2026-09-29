package io.julienmetral.tasks.realtime.sse;

import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.realtime.RealtimeProperties;
import io.julienmetral.tasks.realtime.exceptions.TooManyNotificationStreamsException;
import io.julienmetral.tasks.realtime.messaging.UserNotification;
import io.julienmetral.tasks.support.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationStreamsTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    private static final Duration MAX_DURATION = Duration.ofMinutes(15);

    private static final int MAX_PER_USER = 2;

    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(3);

    private static final int BUFFER_SIZE = 5;

    private static final Instant TOKEN_EXPIRY = NOW.plus(Duration.ofMinutes(10));

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final AccountState ACTIVE = new AccountState(true, Instant.parse("2026-01-01T00:00:00Z"));

    private static final AccountState DISABLED = new AccountState(false, Instant.parse("2026-01-01T00:00:00Z"));

    private static final AccountState UNVERIFIED = new AccountState(true, null);

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    private final TestClock clock = new TestClock();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private UserRepository userRepository;

    private NotificationStreams streams;

    @BeforeEach
    void createStreams() {
        clock.set(NOW);
        streams = new NotificationStreams(
                new RealtimeProperties(10_000, new RealtimeProperties.Streams(
                        // No heartbeat during a test
                        Duration.ofMinutes(10),
                        MAX_DURATION,
                        MAX_PER_USER,
                        RECONNECT_DELAY,
                        Duration.ofMinutes(5),
                        100,
                        BUFFER_SIZE
                ), new RealtimeProperties.Rooms(
                        List.of(), Duration.ofSeconds(10), DataSize.ofKilobytes(16), Duration.ofSeconds(10)
                )),
                userRepository,
                jsonMapper,
                clock,
                meterRegistry
        );
    }

    @AfterEach
    void stopTheWriters() {
        streams.closeAll();
    }

    // Lifetime

    @Test
    void streamEndsWhenTheAccessTokenExpires() {
        accountIs(ALICE, ACTIVE);

        SseEmitter emitter = streams.open(ALICE, NOW.plus(Duration.ofMinutes(10)), null);

        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    void streamEndsAfterTheMaximumDurationWhenTheTokenLastsLonger() {
        accountIs(ALICE, ACTIVE);

        SseEmitter emitter = streams.open(ALICE, NOW.plus(Duration.ofHours(1)), null);

        assertThat(emitter.getTimeout()).isEqualTo(MAX_DURATION.toMillis());
    }

    @Test
    void tokenAlreadyExpiredGivesTheStreamTheShortestLifetime() {
        accountIs(ALICE, ACTIVE);

        SseEmitter emitter = streams.open(ALICE, NOW.minusSeconds(5), null);

        assertThat(emitter.getTimeout()).isEqualTo(1L);
    }

    @Test
    void tokenWithoutExpiryGivesTheStreamTheMaximumDuration() {
        accountIs(ALICE, ACTIVE);

        SseEmitter emitter = streams.open(ALICE, null, null);

        assertThat(emitter.getTimeout()).isEqualTo(MAX_DURATION.toMillis());
    }

    @Test
    void streamStartsWithARetryOfTheReconnectDelayPlusARandomExtraOfUpToAsMuch() {
        accountIs(ALICE, ACTIVE);

        SseClient client = open(ALICE, null);

        String retry = awaitBlocks(client, 1).getFirst();
        assertThat(retry).matches("retry:\\d+");
        assertThat(Long.parseLong(retry.substring("retry:".length())))
                .isBetween(RECONNECT_DELAY.toMillis(), RECONNECT_DELAY.multipliedBy(2).toMillis());
    }

    // Delivery

    @Test
    void notificationReachesTheRecipientsStreamWithItsIdItsTypeAndTheWebhookPayload() {
        accountIs(ALICE, ACTIVE);
        SseClient client = open(ALICE, null);
        UUID eventId = UUID.randomUUID();

        streams.deliver(eventId, new UserNotification(ALICE, "task.assigned", Instant.parse("2026-03-04T05:00:00Z"),
                Map.of("task", Map.of("reference", "OPS-12"))));

        String event = awaitBlocks(client, 2).get(1);
        assertThat(event).startsWith("id:" + eventId + "\nevent:task.assigned\ndata:");
        JsonNode data = jsonMapper.readTree(event.substring(event.indexOf("data:") + "data:".length()));
        assertThat(data.propertyNames()).containsExactly("type", "timestamp", "data");
        assertThat(data).isEqualTo(jsonMapper.valueToTree(Map.of(
                "type", "task.assigned",
                "timestamp", "2026-03-04T05:00:00Z",
                "data", Map.of("task", Map.of("reference", "OPS-12"))
        )));
    }

    @Test
    void notificationOfAnotherUserDoesNotReachTheStream() {
        accountIs(ALICE, ACTIVE);
        SseClient client = open(ALICE, null);
        UUID forBob = deliver(BOB);

        UUID forAlice = deliver(ALICE);

        assertThat(eventIds(awaitBlocks(client, 2))).containsExactly(forAlice.toString());
        assertThat(client.content()).doesNotContain(forBob.toString());
    }

    @Test
    void oneNotificationReachesEveryStreamOfItsRecipientWhole() {
        accountIs(ALICE, ACTIVE);
        SseClient first = open(ALICE, null);
        SseClient second = open(ALICE, null);

        UUID eventId = deliver(ALICE);

        String expected = "id:" + eventId + "\nevent:task.assigned\ndata:";
        assertThat(awaitBlocks(first, 2).get(1)).startsWith(expected);
        assertThat(awaitBlocks(second, 2).get(1)).startsWith(expected);
        assertThat(second.blocks().get(1)).isEqualTo(first.blocks().get(1));
    }

    // Streams per user and account state

    @Test
    void userCannotOpenMoreStreamsThanTheLimitOnThisInstance() {
        accountIs(ALICE, ACTIVE);
        open(ALICE, null);
        open(ALICE, null);

        assertThatExceptionOfType(TooManyNotificationStreamsException.class)
                .isThrownBy(() -> streams.open(ALICE, TOKEN_EXPIRY, null))
                .withMessage("Too many open notification streams: at most 2 per user");
        assertThat(openStreams()).isEqualTo(2);
    }

    @Test
    void limitIsCountedPerUser() {
        accountIs(ALICE, ACTIVE);
        accountIs(BOB, ACTIVE);
        open(ALICE, null);
        open(ALICE, null);

        open(BOB, null);

        assertThat(openStreams()).isEqualTo(3);
    }

    @Test
    void streamWhoseClientLeftFreesItsPlace() {
        accountIs(ALICE, ACTIVE);
        SseClient leaving = open(ALICE, null);
        open(ALICE, null);

        leaving.leave();

        open(ALICE, null);
        assertThat(openStreams()).isEqualTo(2);
    }

    static Stream<Arguments> inactiveAccounts() {
        return Stream.of(
                Arguments.of("disabled", Optional.of(DISABLED)),
                Arguments.of("unverified", Optional.of(UNVERIFIED)),
                Arguments.of("deleted", Optional.empty())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inactiveAccounts")
    void inactiveAccountCannotOpenAStreamAndLeavesNoneOpen(String state, Optional<AccountState> account) {
        when(userRepository.findAccountStateById(ALICE)).thenReturn(account);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> streams.open(ALICE, TOKEN_EXPIRY, null));

        assertThat(openStreams()).isZero();
    }

    @Test
    void failedAccountReadLeavesNoStreamRegistered() {
        accountIs(BOB, ACTIVE);
        open(BOB, null);
        DataAccessResourceFailureException databaseDown = new DataAccessResourceFailureException("Database down");
        doThrow(databaseDown).when(userRepository).findAccountStateById(ALICE);

        for (int attempt = 0; attempt < MAX_PER_USER; attempt++) {
            assertThatThrownBy(() -> streams.open(ALICE, TOKEN_EXPIRY, null)).isSameAs(databaseDown);
        }

        assertThat(openStreams()).isEqualTo(1);
        doReturn(Optional.of(ACTIVE)).when(userRepository).findAccountStateById(ALICE);
        open(ALICE, null);
        open(ALICE, null);
        assertThat(openStreams()).isEqualTo(1 + MAX_PER_USER);
    }

    @Test
    void disablingBroadcastWhileTheStreamOpensClosesIt() {
        AtomicInteger reads = new AtomicInteger();
        when(userRepository.findAccountStateById(ALICE)).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                // open() read the account just before the disabling committed, whose broadcast arrives now
                streams.closeIfNoLongerActive(ALICE);
                return Optional.of(ACTIVE);
            }
            return Optional.of(DISABLED);
        });

        SseClient client = open(ALICE, null);

        assertThat(openStreams()).isZero();
        awaitClosed(client);
    }

    @Test
    void closeIfNoLongerActiveClosesEveryStreamOfAnAccountDisabledSince() {
        accountIs(ALICE, ACTIVE);
        SseClient first = open(ALICE, null);
        SseClient second = open(ALICE, null);
        accountIs(ALICE, DISABLED);

        streams.closeIfNoLongerActive(ALICE);

        assertThat(openStreams()).isZero();
        awaitClosed(first);
        awaitClosed(second);
    }

    @Test
    void closeIfNoLongerActiveLeavesTheStreamsOfAnActiveAccountOpen() {
        accountIs(ALICE, ACTIVE);
        SseClient client = open(ALICE, null);

        streams.closeIfNoLongerActive(ALICE);

        assertThat(openStreams()).isEqualTo(1);
        awaitEvent(client, deliver(ALICE));
        assertThat(client.closed()).isFalse();
    }

    @Test
    void closeIfNoLongerActiveLeavesTheStreamsOfOtherUsersOpen() {
        accountIs(ALICE, ACTIVE);
        accountIs(BOB, ACTIVE);
        SseClient alice = open(ALICE, null);
        open(BOB, null);
        accountIs(BOB, DISABLED);

        streams.closeIfNoLongerActive(BOB);

        assertThat(openStreams()).isEqualTo(1);
        awaitEvent(alice, deliver(ALICE));
        assertThat(alice.closed()).isFalse();
    }

    @Test
    void closeIfNoLongerActiveReadsNoAccountForAUserWithoutStreams() {
        streams.closeIfNoLongerActive(ALICE);

        verifyNoInteractions(userRepository);
    }

    // Slow clients

    @Test
    void clientThatStopsReadingIsDisconnectedWithoutHoldingUpTheListener() throws Exception {
        accountIs(ALICE, ACTIVE);
        SseClient.StallingResponse response = new SseClient.StallingResponse();
        SseClient client = SseClient.connect(streams.open(ALICE, TOKEN_EXPIRY, null), response);
        awaitBlocks(client, 1);
        response.stall();

        try {
            deliver(ALICE);
            assertThat(response.awaitBlockedWrite(WRITE_TIMEOUT)).as("the writer blocked in a write").isTrue();
            for (int i = 0; i < BUFFER_SIZE; i++) {
                deliver(ALICE);
            }

            // Completing the response waits for the blocked write: the listener handing out the event must not
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> deliver(ALICE));
            assertThat(openStreams()).isZero();
        } finally {
            response.resume();
        }

        awaitClosed(client);
    }

    // Replay

    @Test
    void reconnectingWithTheLastEventIdReplaysTheMissedNotificationsInOrder() {
        accountIs(ALICE, ACTIVE);
        UUID received = deliver(ALICE);
        UUID firstMissed = deliver(ALICE);
        UUID secondMissed = deliver(ALICE);

        SseClient client = open(ALICE, received.toString());
        UUID live = deliver(ALICE);

        List<String> blocks = awaitEvent(client, live);
        assertThat(blocks.getFirst()).startsWith("retry:");
        assertThat(eventIds(blocks)).containsExactly(firstMissed.toString(), secondMissed.toString(), live.toString());
        assertThat(client.content()).doesNotContain("event:resync");
    }

    @Test
    void replayLeavesOutTheNotificationsOfOtherUsers() {
        accountIs(ALICE, ACTIVE);
        UUID received = deliver(ALICE);
        deliver(BOB);
        UUID missed = deliver(ALICE);

        SseClient client = open(ALICE, received.toString());
        UUID live = deliver(ALICE);

        assertThat(eventIds(awaitEvent(client, live))).containsExactly(missed.toString(), live.toString());
    }

    @Test
    void unknownLastEventIdGetsAResyncEventInsteadOfAReplay() {
        accountIs(ALICE, ACTIVE);
        deliver(ALICE);

        SseClient client = open(ALICE, UUID.randomUUID().toString());
        UUID live = deliver(ALICE);

        List<String> blocks = awaitEvent(client, live);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1)).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}");
    }

    @Test
    void replayThatWouldFillTheStreamBufferIsReplacedByAResync() {
        accountIs(ALICE, ACTIVE);
        UUID received = deliver(ALICE);
        for (int i = 0; i < BUFFER_SIZE - 1; i++) {
            deliver(ALICE);
        }

        SseClient client = open(ALICE, received.toString());
        UUID live = deliver(ALICE);

        List<String> blocks = awaitEvent(client, live);
        assertThat(blocks.get(1)).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}");
        assertThat(eventIds(blocks)).containsExactly(live.toString());
        assertThat(client.closed()).isFalse();
    }

    @Test
    void replayThatLeavesRoomInTheStreamBufferIsSentWhole() {
        accountIs(ALICE, ACTIVE);
        UUID received = deliver(ALICE);
        List<String> missed = Stream.generate(() -> deliver(ALICE).toString()).limit(BUFFER_SIZE - 2).toList();

        SseClient client = open(ALICE, received.toString());

        List<String> blocks = awaitBlocks(client, BUFFER_SIZE - 1);
        assertThat(eventIds(blocks)).containsExactlyElementsOf(missed);
        assertThat(client.closed()).isFalse();
    }

    @Test
    void streamOpenedWithoutALastEventIdGetsNeitherReplayNorResync() {
        accountIs(ALICE, ACTIVE);
        deliver(ALICE);

        SseClient client = open(ALICE, null);
        UUID live = deliver(ALICE);

        List<String> blocks = awaitEvent(client, live);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst()).startsWith("retry:");
    }

    // After a gap, and at shutdown

    @Test
    void resyncAllSendsAResyncEventToEveryOpenStream() {
        accountIs(ALICE, ACTIVE);
        accountIs(BOB, ACTIVE);
        SseClient alice = open(ALICE, null);
        SseClient bob = open(BOB, null);

        streams.resyncAll();

        assertThat(awaitBlocks(alice, 2).get(1)).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}");
        assertThat(awaitBlocks(bob, 2).get(1)).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}");
    }

    @Test
    void afterResyncAllNoReplayCanSkipTheGap() {
        accountIs(ALICE, ACTIVE);
        UUID received = deliver(ALICE);
        deliver(ALICE);

        streams.resyncAll();
        SseClient client = open(ALICE, received.toString());
        UUID live = deliver(ALICE);

        List<String> blocks = awaitEvent(client, live);
        assertThat(blocks.get(1)).isEqualTo("event:resync\ndata:{\"type\":\"resync\"}");
        assertThat(eventIds(blocks)).containsExactly(live.toString());
    }

    @Test
    void closeAllCompletesEveryOpenStream() {
        accountIs(ALICE, ACTIVE);
        accountIs(BOB, ACTIVE);
        SseClient alice = open(ALICE, null);
        SseClient bob = open(BOB, null);

        streams.closeAll();

        assertThat(openStreams()).isZero();
        awaitClosed(alice);
        awaitClosed(bob);
    }

    // Metrics

    @Test
    void gaugeCountsTheStreamsOpenOnThisInstance() {
        accountIs(ALICE, ACTIVE);
        accountIs(BOB, ACTIVE);
        assertThat(openStreams()).isZero();

        SseClient leaving = open(ALICE, null);
        open(ALICE, null);
        open(BOB, null);
        assertThat(openStreams()).isEqualTo(3);

        leaving.leave();
        assertThat(openStreams()).isEqualTo(2);
    }

    private void accountIs(UUID userId, AccountState state) {
        when(userRepository.findAccountStateById(userId)).thenReturn(Optional.of(state));
    }

    private SseClient open(UUID userId, String lastEventId) {
        return SseClient.connect(streams.open(userId, TOKEN_EXPIRY, lastEventId));
    }

    private UUID deliver(UUID recipientId) {
        UUID eventId = UUID.randomUUID();
        streams.deliver(eventId, new UserNotification(recipientId, "task.assigned", NOW, Map.of()));
        return eventId;
    }

    private double openStreams() {
        return meterRegistry.get("notification.streams.open").gauge().value();
    }

    // The stream is released at once, and its response completed on a thread of its own
    private static void awaitClosed(SseClient client) {
        await().atMost(WRITE_TIMEOUT).until(client::closed);
    }

    private static List<String> awaitBlocks(SseClient client, int count) {
        return await().atMost(WRITE_TIMEOUT).until(client::blocks, blocks -> blocks.size() >= count);
    }

    // The stream's buffer is written in order: once the event sent last is there, everything before it is too
    private static List<String> awaitEvent(SseClient client, UUID eventId) {
        return await().atMost(WRITE_TIMEOUT).until(client::blocks,
                blocks -> eventIds(blocks).contains(eventId.toString()));
    }

    private static List<String> eventIds(List<String> blocks) {
        return blocks.stream()
                .filter(block -> block.startsWith("id:"))
                .map(block -> block.substring("id:".length(), block.indexOf('\n')))
                .toList();
    }
}
