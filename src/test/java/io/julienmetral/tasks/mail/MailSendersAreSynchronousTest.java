package io.julienmetral.tasks.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.julienmetral.tasks.TasksApplication;
import io.julienmetral.tasks.export.mail.DataExportEmailSender;
import io.julienmetral.tasks.identity.mail.InactiveAccountEmailSender;
import io.julienmetral.tasks.identity.mail.PasswordResetEmailSender;
import io.julienmetral.tasks.identity.mail.VerificationEmailSender;
import io.julienmetral.tasks.notification.mail.TaskNotificationSender;
import io.julienmetral.tasks.support.MailOutbox;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.ClassUtils;

/**
 * Integration tests prove that a call sent no email by counting the outbox as soon as the call
 * returns ({@link MailOutbox}). That holds only while every email is written to the outbox before
 * the call returns: a sender made {@code @Async}, or turned into a
 * {@code @TransactionalEventListener} (which runs after the commit), would let those tests pass
 * before the email exists.
 */
class MailSendersAreSynchronousTest {

    private static final String BASE_PACKAGE = "io.julienmetral.tasks";

    @Test
    void scanFindsTheMailSenders() {
        assertThat(mailSenders())
                .contains(
                        TaskNotificationSender.class,
                        VerificationEmailSender.class,
                        PasswordResetEmailSender.class,
                        InactiveAccountEmailSender.class,
                        DataExportEmailSender.class);
    }

    @Test
    void everyMailSenderAndTheMailServiceRunInTheCallersThread() {
        List<Class<?>> checked = new ArrayList<>(mailSenders());
        checked.add(MailService.class);

        assertThat(checked).flatMap(MailSendersAreSynchronousTest::problemsOf).isEmpty();
    }

    @Test
    void asyncListenerIsReported() {
        assertThat(problemsOf(AsyncListener.class))
                .singleElement()
                .asString()
                .contains("AsyncListener.on()", "@Async");
    }

    @Test
    void asyncClassIsReported() {
        assertThat(problemsOf(AsyncClass.class))
                .singleElement()
                .asString()
                .contains("AsyncClass", "@Async");
    }

    @Test
    void listenerRunAfterTheCommitIsReported() {
        assertThat(problemsOf(AfterCommitListener.class))
                .singleElement()
                .asString()
                .contains("AfterCommitListener.on()", "@TransactionalEventListener");
    }

    @Test
    void plainListenerIsAccepted() {
        assertThat(problemsOf(PlainListener.class)).isEmpty();
    }

    // The classes that hold a MailService and listen to events: the senders of every email
    private static List<Class<?>> mailSenders() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {

                    @Override
                    protected boolean isCandidateComponent(MetadataReader reader) {
                        return true;
                    }

                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return true;
                    }
                };

        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .<Class<?>>map(
                        definition ->
                                ClassUtils.resolveClassName(
                                        definition.getBeanClassName(),
                                        MailSendersAreSynchronousTest.class.getClassLoader()))
                .filter(MailSendersAreSynchronousTest::isApplicationClass)
                .filter(MailSendersAreSynchronousTest::holdsMailService)
                .filter(type -> !listeners(type).isEmpty())
                .toList();
    }

    // Test classes, the fixtures below included, share the base package
    private static boolean isApplicationClass(Class<?> type) {
        return type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .equals(TasksApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static boolean holdsMailService(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(MailService.class));
    }

    // @TransactionalEventListener is itself an @EventListener
    private static List<Method> listeners(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, EventListener.class))
                .toList();
    }

    private static List<String> problemsOf(Class<?> type) {
        List<String> problems = new ArrayList<>();

        if (AnnotatedElementUtils.hasAnnotation(type, Async.class)) {
            problems.add(type.getSimpleName() + " carries @Async");
        }

        for (Method method : type.getDeclaredMethods()) {
            String where = type.getSimpleName() + "." + method.getName() + "()";

            if (AnnotatedElementUtils.hasAnnotation(method, Async.class)) {
                problems.add(where + " carries @Async");
            }
            if (AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class)) {
                problems.add(where + " is a @TransactionalEventListener, run after the commit");
            }
        }

        return problems;
    }

    private record Sent() {}

    private static class AsyncListener {

        @Async
        @EventListener
        public void on(Sent event) {}
    }

    @Async
    private static class AsyncClass {

        @EventListener
        public void on(Sent event) {}
    }

    private static class AfterCommitListener {

        @TransactionalEventListener
        public void on(Sent event) {}
    }

    private static class PlainListener {

        @EventListener
        public void on(Sent event) {}
    }
}
