package io.julienmetral.tasks.support;

import io.julienmetral.tasks.media.services.ObjectStorage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An {@link IntegrationTest} whose queue listeners retry only {@link #MAX_RETRIES} times with a short backoff, so a
 * failing message reaches its dead-letter queue in well under a second. SMTP is a mock (so the mail health indicator,
 * which needs a real {@code JavaMailSenderImpl}, is off) and the object storage a spy, for every class that uses it:
 * declaring them here rather than in each class keeps the dead-letter tests in one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@IntegrationTest
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=" + DeadLetterIntegrationTest.MAX_RETRIES,
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=100ms",
        "management.health.mail.enabled=false"
})
@MockitoBean(types = JavaMailSender.class)
@MockitoSpyBean(types = ObjectStorage.class)
public @interface DeadLetterIntegrationTest {

    int MAX_RETRIES = 2;
}
