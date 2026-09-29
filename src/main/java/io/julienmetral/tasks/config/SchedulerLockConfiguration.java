package io.julienmetral.tasks.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.micrometer.MicrometerLockingTaskExecutorListener;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.sql.DatabaseProduct;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock for the jobs of {@link ScheduledJobLocks}: {@code @SchedulerLock} on a job method takes its row in
 * {@code scheduler_locks} before the method runs, and an instance that finds it taken skips that run.
 * <ul>
 *     <li>{@code lockAtMostFor} releases the lock of a crashed instance. It must stay well above the longest run,
 *     or a second instance starts while the first still works, and below the period of a frequent job, so a crash
 *     costs one run at most.</li>
 *     <li>{@code lockAtLeastFor} keeps a quick run's lock for a while, so an instance whose clock fires a few seconds
 *     late finds it taken instead of running the job again.</li>
 * </ul>
 * <p>
 * Kept apart from {@link SchedulingConfiguration}, which tests load without a database to validate the jobs'
 * properties.
 * <p>
 * Warning: put {@code @SchedulerLock} on the job method, never on a {@code @Transactional} one. The lock is written in
 * its own transaction, so on a transactional method it would be released before the work commits.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfiguration {

    static final String TABLE = "scheduler_locks";

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(lockProviderConfiguration(jdbcTemplate).build());
    }

    /**
     * Every lock time comes from the database clock, so the instances' clocks may drift apart. The product is set
     * because detecting it would open a connection on the first lock.
     */
    static JdbcTemplateLockProvider.Configuration.Builder lockProviderConfiguration(JdbcTemplate jdbcTemplate) {
        return JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName(TABLE)
                .withDatabaseProduct(DatabaseProduct.POSTGRES_SQL)
                .usingDbTime();
    }

    @Bean
    MicrometerLockingTaskExecutorListener schedulerLockMetrics(MeterRegistry meterRegistry) {
        MicrometerLockingTaskExecutorListener listener = new MicrometerLockingTaskExecutorListener(meterRegistry);
        listener.registerMetricsFor(ScheduledJobLocks.NAMES.toArray(String[]::new));
        return listener;
    }
}
