package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.config.UserRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "identity.retention.enabled=true")
class UserRetentionJobTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserRetentionProperties properties;

    @Test
    void enabledJobIsScheduledOnTheConfiguredCronDailyAtFour() {
        assertThat(context.getBeansOfType(UserRetentionJob.class)).hasSize(1);

        List<CronTask> retentionTasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(task -> task.getRunnable().toString().contains(UserRetentionJob.class.getName()))
                .toList();

        assertThat(retentionTasks).singleElement()
                .extracting(CronTask::getExpression)
                .isEqualTo(properties.cron())
                .isEqualTo("0 0 4 * * *");
    }
}
