package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.realtime.RealtimeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompConfigurationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final List<String> ALLOWED_ORIGINS = List.of("https://app.example.com", "https://*.example.org");

    @Mock
    private StompAuthentication stompAuthentication;

    @Mock
    private TaskRoomAuthorization taskRoomAuthorization;

    @Mock
    private StompSessions stompSessions;

    @Mock
    private TaskScheduler brokerScheduler;

    @Captor
    private ArgumentCaptor<MessageAuthorizationContext<?>> roomContext;

    private final List<Message<?>> delivered = new ArrayList<>();

    @Test
    void inboundFramesAreAuthenticatedThenGivenTheirSecurityContextThenAuthorized() {
        List<ChannelInterceptor> interceptors = inboundInterceptors();

        assertThat(interceptors).hasSize(3);
        assertThat(interceptors.get(0)).isSameAs(stompAuthentication);
        assertThat(interceptors.get(1)).isInstanceOf(SecurityContextChannelInterceptor.class);
        assertThat(interceptors.get(2)).isInstanceOf(AuthorizationChannelInterceptor.class);
    }

    @Test
    void connectOfAnAuthenticatedUserIsAllowed() {
        ExecutorSubscribableChannel inbound = inboundChannel();

        inbound.send(frame(StompCommand.CONNECT, null, user()));

        assertThat(delivered).hasSize(1);
    }

    @Test
    void connectWithoutAUserIsRefused() {
        ExecutorSubscribableChannel inbound = inboundChannel();

        assertRefused(inbound, frame(StompCommand.CONNECT, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"UNSUBSCRIBE", "DISCONNECT"})
    void unsubscribeAndDisconnectNeedNoUser(StompCommand command) {
        ExecutorSubscribableChannel inbound = inboundChannel();

        inbound.send(frame(command, null, null));

        assertThat(delivered).hasSize(1);
    }

    @Test
    void heartbeatNeedsNoUser() {
        ExecutorSubscribableChannel inbound = inboundChannel();
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setLeaveMutable(true);

        inbound.send(MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders()));

        assertThat(delivered).hasSize(1);
    }

    @Test
    void subscriptionToATaskRoomIsDecidedByTheRoomAuthorizationWithTheTaskIdOfTheDestination() {
        ExecutorSubscribableChannel inbound = inboundChannel();
        when(taskRoomAuthorization.authorize(any(), any())).thenReturn(new AuthorizationDecision(true));

        inbound.send(frame(StompCommand.SUBSCRIBE, "/topic/tasks/" + TASK_ID, user()));

        assertThat(delivered).hasSize(1);
        verify(taskRoomAuthorization).authorize(any(), roomContext.capture());
        assertThat(roomContext.getValue().getVariables()).containsEntry("taskId", TASK_ID.toString());
    }

    @Test
    void subscriptionTheRoomAuthorizationRefusesIsRefused() {
        ExecutorSubscribableChannel inbound = inboundChannel();
        when(taskRoomAuthorization.authorize(any(), any())).thenReturn(new AuthorizationDecision(false));

        assertRefused(inbound, frame(StompCommand.SUBSCRIBE, "/topic/tasks/" + TASK_ID, user()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/other",
            "/topic/tasks",
            "/topic/tasks/00000000-0000-0000-0000-0000000000b1/comments",
            "/queue/tasks/00000000-0000-0000-0000-0000000000b1",
            "/user/queue/errors"
    })
    void subscriptionOutsideTheTaskRoomsIsRefused(String destination) {
        ExecutorSubscribableChannel inbound = inboundChannel();
        lenient().when(taskRoomAuthorization.authorize(any(), any())).thenReturn(new AuthorizationDecision(true));

        assertRefused(inbound, frame(StompCommand.SUBSCRIBE, destination, user()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/tasks/00000000-0000-0000-0000-0000000000b1", "/app/tasks", "/user/queue/errors"})
    void frameSentToADestinationIsRefusedEvenWhereTheUserMaySubscribe(String destination) {
        ExecutorSubscribableChannel inbound = inboundChannel();
        lenient().when(taskRoomAuthorization.authorize(any(), any())).thenReturn(new AuthorizationDecision(true));

        assertRefused(inbound, frame(StompCommand.SEND, destination, user()));
    }

    @Test
    void refusalAnswersAnErrorFrameWithTheReasonTheChannelWrapped() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null, null);
        MessageDeliveryException wrapped = new MessageDeliveryException(
                connect, "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]",
                new BadCredentialsException("Invalid access token"));

        Message<byte[]> error = errorHandler(ALLOWED_ORIGINS).handleClientMessageProcessingError(connect, wrapped);

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(headers.getMessage()).isEqualTo("Invalid access token");
    }

    @Test
    void deliveryFailureWithoutACauseKeepsItsOwnMessage() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null, null);
        MessageDeliveryException failure = new MessageDeliveryException(connect, "No subscriber");

        Message<byte[]> error = errorHandler(ALLOWED_ORIGINS).handleClientMessageProcessingError(connect, failure);

        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo("No subscriber");
    }

    @Test
    void otherFailureKeepsItsOwnMessage() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null, null);

        Message<byte[]> error = errorHandler(ALLOWED_ORIGINS).handleClientMessageProcessingError(
                connect, new IllegalStateException("Frame too large", new RuntimeException("hidden")));

        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo("Frame too large");
    }

    @Test
    void endpointIsWsWithoutSockJsAndAllowsTheConfiguredOriginPatterns() {
        StompWebSocketEndpointRegistration endpoint = registerEndpoint(ALLOWED_ORIGINS).endpoint();

        verify(endpoint).setAllowedOriginPatterns("https://app.example.com", "https://*.example.org");
        verify(endpoint, never()).withSockJS();
    }

    @Test
    void endpointWithoutConfiguredOriginsAllowsNoOtherOrigin() {
        StompWebSocketEndpointRegistration endpoint = registerEndpoint(List.of()).endpoint();

        verify(endpoint).setAllowedOriginPatterns();
    }

    @Test
    void simpleBrokerOnTopicsSendsHeartbeatsOnTheBrokerSchedulerAndKeepsThePublishOrder() {
        ExposedBrokerRegistry registry = new ExposedBrokerRegistry();

        configuration(ALLOWED_ORIGINS).configureMessageBroker(registry);

        SimpleBrokerMessageHandler broker = registry.simpleBroker();
        assertThat(broker.getDestinationPrefixes()).containsExactly("/topic");
        assertThat(broker.getHeartbeatValue()).containsExactly(7_000, 7_000);
        assertThat(broker.getTaskScheduler()).isSameAs(brokerScheduler);
        assertThat(broker.isPreservePublishOrder()).isTrue();
    }

    @Test
    void transportLimitsComeFromTheRoomsSettingsAndEveryConnectionIsTracked() {
        ExposedTransportRegistration registration = new ExposedTransportRegistration();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        configuration(ALLOWED_ORIGINS).configureWebSocketTransport(registration);
        registration.decoratorFactories().forEach(factory -> factory.decorate(handler));

        assertThat(registration.messageSizeLimit()).isEqualTo(32 * 1024);
        assertThat(registration.timeToFirstMessage()).isEqualTo(5_000);
        verify(stompSessions).track(handler);
    }

    private StompConfiguration configuration(List<String> allowedOrigins) {
        RealtimeProperties properties = new RealtimeProperties(10_000,
                new RealtimeProperties.Streams(Duration.ofSeconds(20), Duration.ofMinutes(15), 5,
                        Duration.ofSeconds(3), Duration.ofMinutes(5), 10_000, 100),
                new RealtimeProperties.Rooms(allowedOrigins, Duration.ofSeconds(7), DataSize.ofKilobytes(32),
                        Duration.ofSeconds(5)));

        return new StompConfiguration(
                properties, stompAuthentication, taskRoomAuthorization, stompSessions, brokerScheduler);
    }

    private List<ChannelInterceptor> inboundInterceptors() {
        ExposedChannelRegistration registration = new ExposedChannelRegistration();
        configuration(ALLOWED_ORIGINS).configureClientInboundChannel(registration);

        return registration.interceptors();
    }

    // The client inbound channel with the application's interceptors, token authentication left out: it has its own
    // tests, and here the user is set on the frame as it would have set it
    private ExecutorSubscribableChannel inboundChannel() {
        when(stompAuthentication.preSend(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.setInterceptors(inboundInterceptors());
        channel.subscribe(delivered::add);

        return channel;
    }

    private void assertRefused(ExecutorSubscribableChannel inbound, Message<byte[]> frame) {
        assertThatThrownBy(() -> inbound.send(frame))
                .isInstanceOf(MessageDeliveryException.class)
                .cause()
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access Denied");
        assertThat(delivered).isEmpty();
    }

    private record RegisteredEndpoint(StompEndpointRegistry registry, StompWebSocketEndpointRegistration endpoint) {
    }

    private RegisteredEndpoint registerEndpoint(List<String> allowedOrigins) {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class, RETURNS_SELF);
        when(registry.addEndpoint("/ws")).thenReturn(endpoint);

        configuration(allowedOrigins).registerStompEndpoints(registry);

        return new RegisteredEndpoint(registry, endpoint);
    }

    private StompSubProtocolErrorHandler errorHandler(List<String> allowedOrigins) {
        StompEndpointRegistry registry = registerEndpoint(allowedOrigins).registry();
        ArgumentCaptor<StompSubProtocolErrorHandler> errorHandler =
                ArgumentCaptor.forClass(StompSubProtocolErrorHandler.class);
        verify(registry).setErrorHandler(errorHandler.capture());

        return errorHandler.getValue();
    }

    private static Message<byte[]> frame(StompCommand command, String destination, JwtAuthenticationToken user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        if (destination != null) {
            accessor.setDestination(destination);
            accessor.setSubscriptionId("subscription-1");
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static JwtAuthenticationToken user() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").claim("uid", USER_ID.toString()).build();

        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    private static final class ExposedChannelRegistration extends ChannelRegistration {

        List<ChannelInterceptor> interceptors() {
            return getInterceptors();
        }
    }

    private static final class ExposedBrokerRegistry extends MessageBrokerRegistry {

        ExposedBrokerRegistry() {
            super(new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());
        }

        SimpleBrokerMessageHandler simpleBroker() {
            return getSimpleBroker(new ExecutorSubscribableChannel());
        }
    }

    private static final class ExposedTransportRegistration extends WebSocketTransportRegistration {

        Integer messageSizeLimit() {
            return getMessageSizeLimit();
        }

        Integer timeToFirstMessage() {
            return getTimeToFirstMessage();
        }

        List<WebSocketHandlerDecoratorFactory> decoratorFactories() {
            return getDecoratorFactories();
        }
    }
}
