package io.julienmetral.tasks.support;

import io.julienmetral.tasks.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The full application against the test containers, with MockMvc, Mailpit and the {@link TestClock}. The clock is
 * reset and the caches are emptied after every test.
 * <p>
 * Warning: Spring caches one context per distinct test configuration, and each context starts its own containers.
 * Adding a property, a mock or an import to a single class creates another context: prefer this annotation alone,
 * and reach for {@code @TestPropertySource} or {@code @MockitoBean} only when the test cannot work otherwise.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, Mailpit.class, TestClock.class})
@ExtendWith({TestClock.ResetAfterEach.class, ClearCachesAfterEach.class})
public @interface IntegrationTest {
}
