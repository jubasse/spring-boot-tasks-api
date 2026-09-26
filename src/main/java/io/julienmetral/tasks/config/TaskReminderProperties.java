package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled         run the scheduled reminder job
 * @param cron            when it runs (Spring cron, six fields), in the server time zone
 * @param dueSoonLeadTime how long before the due date the assignee gets the due-soon reminder
 * @param overdueLookback how far back an overdue task still gets its reminder; bounds the first run after an
 *                        outage or a deployment, which would otherwise email every task ever overdue
 */
@Validated
@ConfigurationProperties(prefix = "task.reminders")
public record TaskReminderProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String cron,
        @NotNull Duration dueSoonLeadTime,
        @NotNull Duration overdueLookback
) {
}
