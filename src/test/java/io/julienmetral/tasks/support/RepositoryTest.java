package io.julienmetral.tasks.support;

import io.julienmetral.tasks.PostgresTestcontainersConfiguration;
import io.julienmetral.tasks.shared.config.JpaAuditingConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JPA only, against the shared Postgres migrated by Liquibase: every entity and Spring Data repository, plus
 * {@code TestEntityManager}. Each test runs in a transaction rolled back at its end.
 * <p>
 * Warning: the database is shared with every other test context and with earlier runs, whose rows are committed. Use
 * unique emails and references, and never assert on global counts.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
// JpaAuditingConfiguration fills created_at and updated_at, which are NOT NULL
@Import({PostgresTestcontainersConfiguration.class, JpaAuditingConfiguration.class})
public @interface RepositoryTest {
}
