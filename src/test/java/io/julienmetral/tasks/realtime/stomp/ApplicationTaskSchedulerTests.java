package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @EnableWebSocketMessageBroker} adds the broker's scheduler to the context, next to the one Boot builds for the
 * {@code @Scheduled} jobs.
 */
@IntegrationTest
class ApplicationTaskSchedulerTests {

    private record SchedulerThread(String name, boolean virtual) {
    }

    @Autowired
    private BeanFactory beanFactory;

    @Autowired
    @Qualifier("taskScheduler")
    private TaskScheduler taskScheduler;

    @Autowired
    @Qualifier("messageBrokerTaskScheduler")
    private TaskScheduler brokerScheduler;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    // ScheduledAnnotationBeanPostProcessor resolves the jobs' scheduler through this router: the only scheduler bean,
    // otherwise the one named taskScheduler
    @Test
    void scheduledJobsRunOnTheVirtualThreadsOfBootsSchedulerAndNotOnTheBrokersPool() throws Exception {
        TaskSchedulerRouter router = new TaskSchedulerRouter();
        router.setBeanFactory(beanFactory);
        CompletableFuture<SchedulerThread> thread = new CompletableFuture<>();

        try {
            router.schedule(() -> thread.complete(
                    new SchedulerThread(Thread.currentThread().getName(), Thread.currentThread().isVirtual())
            ), Instant.now());

            SchedulerThread ranOn = thread.get(10, TimeUnit.SECONDS);
            assertThat(ranOn.name()).startsWith("scheduling-");
            assertThat(ranOn.virtual()).isTrue();
        } finally {
            router.destroy();
        }
    }

    @Test
    void applicationSchedulerIsBootsAndNotTheBrokers() {
        assertThat(taskScheduler).isInstanceOf(SimpleAsyncTaskScheduler.class).isNotSameAs(brokerScheduler);
    }

    @Test
    void brokerSendsItsHeartbeatsOnItsOwnPool() throws Exception {
        CompletableFuture<String> thread = new CompletableFuture<>();

        broker.getTaskScheduler().schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());

        assertThat(thread.get(10, TimeUnit.SECONDS)).startsWith("MessageBroker-");
        assertThat(broker.getHeartbeatValue()).containsExactly(10_000, 10_000);
    }
}
