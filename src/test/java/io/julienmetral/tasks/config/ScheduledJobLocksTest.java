package io.julienmetral.tasks.config;

import io.julienmetral.tasks.TasksApplication;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class ScheduledJobLocksTest {

    private static final String BASE_PACKAGE = "io.julienmetral.tasks";

    // Every instance polls them on purpose: FOR UPDATE SKIP LOCKED shares the due rows, where a lock would leave all
    // instances but one idle
    private static final Set<String> UNLOCKED_POLLERS = Set.of(
            "OutboxRelayJob.publishDue",
            "WebhookRetryJob.enqueueDueRetries"
    );

    private static final Duration DEFAULT_LOCK_AT_LEAST_FOR = DurationStyle.detectAndParse(
            SchedulerLockConfiguration.class.getAnnotation(EnableSchedulerLock.class).defaultLockAtLeastFor());

    // The size of scheduler_locks.name: a longer name fails every lock attempt, so the job never runs
    private static final int MAX_NAME_LENGTH = 64;

    @Test
    void scanFindsTheScheduledJobs() {
        assertThat(scheduledMethods()).extracting(ScheduledJobLocksTest::nameOf)
                .contains("RateLimitCleanupJob.deleteOldWindows", "WebhookDeliveryPurgeJob.purgeOldDeliveries",
                        "DataExportPurgeJob.purge", "DataExportRecoveryJob.requeueInterrupted")
                .containsAll(UNLOCKED_POLLERS);
    }

    @Test
    void everyScheduledMethodIsLockedOrAnUnlockedPoller() {
        assertThat(lockProblems(scheduledMethods())).isEmpty();
    }

    @Test
    void everyListedLockNameIsUsedByExactlyOneScheduledMethod() {
        assertThat(methodsByLockName(scheduledMethods()))
                .containsOnlyKeys(ScheduledJobLocks.NAMES)
                .allSatisfy((name, methods) -> assertThat(methods).as(name).hasSize(1));
    }

    @Test
    void listedLockNamesAreDistinctAndFitTheirColumn() {
        assertThat(ScheduledJobLocks.NAMES).doesNotHaveDuplicates().allSatisfy(name ->
                assertThat(name).isNotBlank().hasSizeLessThanOrEqualTo(MAX_NAME_LENGTH));
    }

    @Test
    void scheduledMethodWithoutLockIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(Unlocked.class))).singleElement().asString()
                .contains("Unlocked.run()", "no @SchedulerLock");
    }

    @Test
    void lockNameMissingFromTheListIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(UnlistedName.class))).singleElement().asString()
                .contains("UnlistedName.run()", "'unlisted-lock'", "ScheduledJobLocks.NAMES");
    }

    @Test
    void lockWithoutLockAtMostForIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(DefaultLockAtMostFor.class))).singleElement().asString()
                .contains("DefaultLockAtMostFor.run()", "lockAtMostFor");
    }

    @Test
    void lockAtLeastForNotShorterThanLockAtMostForIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(LockAtLeastForTooLong.class))).singleElement().asString()
                .contains("LockAtLeastForTooLong.run()", "lockAtLeastFor");
    }

    @Test
    void lockedMethodThatIsNotPublicOrIsFinalIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(HiddenFromTheProxy.class))).containsExactlyInAnyOrder(
                "HiddenFromTheProxy.packagePrivate() must be public for the lock proxy",
                "HiddenFromTheProxy.finalMethod() must not be final for the lock proxy"
        );
    }

    @Test
    void lockNameUsedByTwoMethodsIsReported() {
        assertThat(lockProblems(scheduledMethodsOf(SharedName.class))).singleElement().asString()
                .contains(ScheduledJobLocks.RATE_LIMIT_PURGE, "SharedName.first", "SharedName.second");
    }

    private static List<Method> scheduledMethods() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {

            // Instead of the include filters, whose match also evaluates @Conditional: a job switched off here still
            // runs where its property is on
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) {
                AnnotationMetadata metadata = reader.getAnnotationMetadata();
                return metadata.hasAnnotatedMethods(Scheduled.class.getName())
                        || metadata.hasAnnotatedMethods(Schedules.class.getName());
            }

            // Abstract and nested classes too
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };

        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .<Class<?>>map(definition -> ClassUtils.resolveClassName(
                        definition.getBeanClassName(),
                        ScheduledJobLocksTest.class.getClassLoader()))
                .filter(ScheduledJobLocksTest::isApplicationClass)
                .flatMap(type -> scheduledMethodsOf(type).stream())
                .toList();
    }

    // Test classes, the fixtures below included, share the base package
    private static boolean isApplicationClass(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation()
                .equals(TasksApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static List<Method> scheduledMethodsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isBridge() && !method.isSynthetic())
                .filter(method -> !AnnotatedElementUtils
                        .getMergedRepeatableAnnotations(method, Scheduled.class, Schedules.class).isEmpty())
                .toList();
    }

    private static List<String> lockProblems(List<Method> scheduled) {
        List<String> problems = new ArrayList<>();
        scheduled.forEach(method -> problems.addAll(lockProblemsOf(method)));
        methodsByLockName(scheduled).forEach((name, methods) -> {
            if (methods.size() > 1) {
                problems.add("lock '" + name + "' is taken by " + methods);
            }
        });
        return problems;
    }

    private static List<String> lockProblemsOf(Method method) {
        String where = nameOf(method) + "()";
        SchedulerLock lock = lockOf(method);

        if (UNLOCKED_POLLERS.contains(nameOf(method))) {
            return lock == null ? List.of() : List.of(where + " is an unlocked poller but carries @SchedulerLock");
        }
        if (lock == null) {
            return List.of(where + " carries no @SchedulerLock");
        }

        List<String> problems = new ArrayList<>();
        if (!ScheduledJobLocks.NAMES.contains(lock.name())) {
            problems.add(where + " locks '" + lock.name() + "', which ScheduledJobLocks.NAMES does not list");
        }
        if (lock.lockAtMostFor().isEmpty()) {
            problems.add(where + " falls back to the default lockAtMostFor");
        } else if (lockAtLeastFor(lock).compareTo(DurationStyle.detectAndParse(lock.lockAtMostFor())) >= 0) {
            problems.add(where + " has a lockAtLeastFor that is not shorter than its lockAtMostFor");
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            problems.add(where + " must be public for the lock proxy");
        }
        if (Modifier.isFinal(method.getModifiers())) {
            problems.add(where + " must not be final for the lock proxy");
        }
        return problems;
    }

    private static Map<String, List<String>> methodsByLockName(List<Method> scheduled) {
        return scheduled.stream()
                .filter(method -> lockOf(method) != null)
                .collect(groupingBy(
                        method -> lockOf(method).name(),
                        TreeMap::new,
                        mapping(ScheduledJobLocksTest::nameOf, toList())
                ));
    }

    // Read as ShedLock reads it, meta-annotations included
    private static SchedulerLock lockOf(Method method) {
        return AnnotatedElementUtils.getMergedAnnotation(method, SchedulerLock.class);
    }

    private static Duration lockAtLeastFor(SchedulerLock lock) {
        return lock.lockAtLeastFor().isEmpty()
                ? DEFAULT_LOCK_AT_LEAST_FOR
                : DurationStyle.detectAndParse(lock.lockAtLeastFor());
    }

    private static String nameOf(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private static class Unlocked {

        @Scheduled(fixedDelay = 1000)
        public void run() {
        }
    }

    private static class UnlistedName {

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = "unlisted-lock", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
        public void run() {
        }
    }

    private static class DefaultLockAtMostFor {

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.RATE_LIMIT_PURGE)
        public void run() {
        }
    }

    private static class LockAtLeastForTooLong {

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.RATE_LIMIT_PURGE, lockAtMostFor = "PT10M", lockAtLeastFor = "10m")
        public void run() {
        }
    }

    private static class HiddenFromTheProxy {

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.RATE_LIMIT_PURGE, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
        void packagePrivate() {
        }

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.OUTBOX_PURGE, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
        public final void finalMethod() {
        }
    }

    private static class SharedName {

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.RATE_LIMIT_PURGE, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
        public void first() {
        }

        @Scheduled(fixedDelay = 1000)
        @SchedulerLock(name = ScheduledJobLocks.RATE_LIMIT_PURGE, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
        public void second() {
        }
    }
}
