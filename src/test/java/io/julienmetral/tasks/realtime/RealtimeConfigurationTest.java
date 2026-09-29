package io.julienmetral.tasks.realtime;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeConfigurationTest {

    private final RealtimeConfiguration configuration = new RealtimeConfiguration();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RealtimeProperties.class)
    static class PropertiesOnly {
    }

    @Test
    void exchangeIsADurableFanoutNamedTasksRealtime() {
        FanoutExchange exchange = configuration.realtimeExchange();

        assertThat(exchange.getName()).isEqualTo("tasks.realtime");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    void queueOfTheInstanceIsExclusiveAutoDeleteAndBoundedByTheConfiguredLength() {
        AnonymousQueue queue = configuration.realtimeQueue(new RealtimeProperties(42, streams(), rooms()));

        assertThat(queue.getName()).startsWith("tasks.realtime.");
        assertThat(queue.isExclusive()).isTrue();
        assertThat(queue.isAutoDelete()).isTrue();
        assertThat(queue.isDurable()).isFalse();
        assertThat(queue.getArguments()).containsEntry("x-max-length", 42);
    }

    @Test
    void notificationStreamRequestsAreNotObserved() {
        ObservationPredicate predicate = configuration.noObservationOfNotificationStreams();

        assertThat(predicate.test("http.server.requests", serverRequest("/api/v1/notifications/stream"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/tasks", "/api/v1/users/1/notification-settings", "/api/v1/notifications"})
    void otherRequestsAreObserved(String uri) {
        ObservationPredicate predicate = configuration.noObservationOfNotificationStreams();

        assertThat(predicate.test("http.server.requests", serverRequest(uri))).isTrue();
    }

    @Test
    void observationsOtherThanServerRequestsAreKept() {
        ObservationPredicate predicate = configuration.noObservationOfNotificationStreams();

        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isTrue();
    }

    @Test
    void propertiesDefaultToTheDocumentedValues() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RealtimeProperties properties = context.getBean(RealtimeProperties.class);
            assertThat(properties.queueMaxLength()).isEqualTo(10_000);
            assertThat(properties.streams()).isEqualTo(new RealtimeProperties.Streams(
                    Duration.ofSeconds(20), Duration.ofMinutes(15), 5, Duration.ofSeconds(3),
                    Duration.ofMinutes(5), 10_000, 100));
            assertThat(properties.rooms()).isEqualTo(rooms());
        });
    }

    @Test
    void allowedOriginsOfTheRoomsAreReadAsACommaSeparatedList() {
        runner.withPropertyValues("realtime.rooms.allowed-origins=https://app.example.com,https://*.example.org")
                .run(context -> assertThat(context.getBean(RealtimeProperties.class).rooms().allowedOrigins())
                        .containsExactly("https://app.example.com", "https://*.example.org"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "realtime.queue-max-length=0",
            "realtime.streams.heartbeat=0s",
            "realtime.streams.max-duration=25h",
            "realtime.streams.max-per-user=0",
            "realtime.streams.reconnect-delay=50ms",
            "realtime.streams.replay-size=0",
            "realtime.streams.buffer-size=0",
            "realtime.rooms.heartbeat=500ms",
            "realtime.rooms.time-to-first-message=0s"
    })
    void outOfRangeSettingStopsTheStartup(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    private static RealtimeProperties.Streams streams() {
        return new RealtimeProperties.Streams(Duration.ofSeconds(20), Duration.ofMinutes(15), 5, Duration.ofSeconds(3),
                Duration.ofMinutes(5), 10_000, 100);
    }

    private static RealtimeProperties.Rooms rooms() {
        return new RealtimeProperties.Rooms(List.of(), Duration.ofSeconds(10), DataSize.ofKilobytes(16),
                Duration.ofSeconds(10));
    }

    private static ServerRequestObservationContext serverRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
