package io.julienmetral.tasks.support;

import io.julienmetral.tasks.PostgresTestcontainersConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JDBC only, against the shared Postgres migrated by Liquibase: {@code JdbcTemplate},
 * {@code NamedParameterJdbcTemplate} and every native SQL class ({@code *Queries}). Each test runs in a transaction
 * rolled back at its end.
 * <p>
 * Warning: the database is shared with every other test context and with earlier runs, whose rows are committed. Use
 * unique values, and never assert on global counts.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
// Every *Queries class in one context: importing only the class under test would create one context per test class
@JdbcTest(includeFilters = @Filter(type = FilterType.REGEX, pattern = ".*Queries"))
@Import(PostgresTestcontainersConfiguration.class)
public @interface JdbcSliceTest {
}
