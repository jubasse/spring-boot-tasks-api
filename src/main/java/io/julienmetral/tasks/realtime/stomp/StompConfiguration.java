package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.realtime.RealtimeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Task rooms over STOMP: clients connect to {@value #ENDPOINT} and subscribe to {@code /topic/tasks/{id}}, where the
 * simple broker of each instance sends the room events it receives (see {@code RealtimeListener}). Clients only
 * subscribe: every frame they send to a destination is refused.
 * <p>
 * Security is wired here rather than with {@code @EnableWebSocketSecurity}, whose CSRF check on CONNECT cannot be
 * turned off. That check protects cookie sessions; this API has none, and the token travels in the frame.
 */
@Configuration
@EnableWebSocketMessageBroker
// Before Spring's own configurers, so the interceptors see CONNECT first (Spring Framework's token authentication guide)
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompConfiguration implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    private final RealtimeProperties.Rooms properties;
    private final StompAuthentication stompAuthentication;
    private final TaskRoomAuthorization taskRoomAuthorization;
    private final StompSessions stompSessions;
    private final TaskScheduler brokerScheduler;

    public StompConfiguration(
            RealtimeProperties properties,
            StompAuthentication stompAuthentication,
            TaskRoomAuthorization taskRoomAuthorization,
            StompSessions stompSessions,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler
    ) {
        this.properties = properties.rooms();
        this.stompAuthentication = stompAuthentication;
        this.taskRoomAuthorization = taskRoomAuthorization;
        this.stompSessions = stompSessions;
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Warning: not setPreserveReceiveOrder. Its decorator sends frames asynchronously and only logs what an
        // interceptor throws: a refused CONNECT got no ERROR frame, stayed open without a user, and was logged at ERROR
        // with its headers. Without it, the interceptors still run in order, on the connection's thread.
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(new RefusalReasons());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.heartbeat().toMillis();

        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {heartbeat, heartbeat})
                .setTaskScheduler(brokerScheduler);
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                stompAuthentication,
                new SecurityContextChannelInterceptor(),
                new AuthorizationChannelInterceptor(authorizationManager())
        );
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit((int) properties.messageSizeLimit().toBytes())
                .setTimeToFirstMessage((int) properties.timeToFirstMessage().toMillis())
                .addDecoratorFactory(stompSessions::track);
    }

    private AuthorizationManager<Message<?>> authorizationManager() {
        return MessageMatcherDelegatingAuthorizationManager.builder()
                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                .simpTypeMatchers(SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE, SimpMessageType.DISCONNECT)
                .permitAll()
                .simpSubscribeDestMatchers(TaskRooms.TOPIC_PREFIX + "{" + TaskRoomAuthorization.TASK_ID + "}")
                .access(taskRoomAuthorization)
                .anyMessage().denyAll()
                .build();
    }

    /**
     * Boot's own {@code taskScheduler}, as Boot declares it. Warning: {@code @EnableWebSocketMessageBroker} defines
     * {@code messageBrokerTaskScheduler}, a scheduler bean, and Boot then backs off from its own: every
     * {@code @Scheduled} job silently moved to the broker's pool of heartbeat threads.
     */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationTaskScheduler {

        @Bean(name = "taskScheduler")
        @ConditionalOnThreading(Threading.VIRTUAL)
        SimpleAsyncTaskScheduler taskSchedulerVirtualThreads(SimpleAsyncTaskSchedulerBuilder builder) {
            return builder.build();
        }

        @Bean(name = "taskScheduler")
        @ConditionalOnThreading(Threading.PLATFORM)
        ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
            return builder.build();
        }
    }

    // The ERROR frame of a refusal carries the channel's generic "Failed to send message"; the reason is the cause,
    // one of the messages of StompAuthentication or Spring Security's "Access Denied"
    private static final class RefusalReasons extends StompSubProtocolErrorHandler {

        @Override
        public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
            Throwable reason = ex instanceof MessageDeliveryException && ex.getCause() != null ? ex.getCause() : ex;

            return super.handleClientMessageProcessingError(clientMessage, reason);
        }
    }
}
