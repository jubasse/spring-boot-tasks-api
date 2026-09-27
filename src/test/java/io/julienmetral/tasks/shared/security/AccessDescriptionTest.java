package io.julienmetral.tasks.shared.security;

import io.julienmetral.tasks.identity.controllers.AuthController;
import io.julienmetral.tasks.identity.controllers.UserController;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.task.controllers.TaskController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AccessDescriptionTest {

    private static final String BASE_PACKAGE = "io.julienmetral.tasks";

    private static final List<Class<? extends Annotation>> SECURITY_RULES =
            List.of(PreAuthorize.class, PostAuthorize.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    @Test
    void scanFindsTheControllers() {
        assertThat(controllers()).contains(AuthController.class, UserController.class, TaskController.class);
    }

    @Test
    void everySecurityRuleOfAControllerIsDescribed() {
        assertThat(controllers()).allSatisfy(controller ->
                assertThat(undescribedRules(controller)).as(controller.getSimpleName()).isEmpty());
    }

    @Test
    void everyPlaceholderOfTheDescriptionsInUseNamesAnAttribute() {
        Set<Class<? extends Annotation>> described = new LinkedHashSet<>();
        for (Class<?> controller : controllers()) {
            handlers(controller).forEach(handler -> Arrays.stream(handler.getDeclaredAnnotations())
                    .map(Annotation::annotationType)
                    .filter(type -> type.isAnnotationPresent(AccessDescription.class))
                    .forEach(described::add));
        }

        assertThat(described).isNotEmpty().allSatisfy(type ->
                assertThat(unresolvedPlaceholders(type)).as(type.getSimpleName()).isEmpty());
    }

    @Test
    void inlinePreAuthorizeOnAHandlerIsReported() {
        assertThat(undescribedRules(InlineRule.class)).singleElement().asString()
                .contains("handle()", "@PreAuthorize");
    }

    @Test
    void securityAnnotationWithoutAccessDescriptionIsReported() {
        assertThat(undescribedRules(UndescribedRule.class)).singleElement().asString()
                .contains("handle()", "@Undescribed");
    }

    @Test
    void securityAnnotationOnAControllerClassIsReported() {
        assertThat(undescribedRules(ClassLevelRule.class)).singleElement().asString()
                .contains("ClassLevelRule");
    }

    @Test
    void placeholderNamingNoAttributeIsReported() {
        assertThat(unresolvedPlaceholders(MisspelledPlaceholder.class)).containsExactly("roles");
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .<Class<?>>map(definition -> ClassUtils.resolveClassName(
                        definition.getBeanClassName(),
                        AccessDescriptionTest.class.getClassLoader()))
                .toList();
    }

    private static List<Method> handlers(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }

    private static List<String> undescribedRules(Class<?> controller) {
        List<String> undescribed = new ArrayList<>();

        // OperationDocumentation reads the annotations of the handler method only
        if (isSecurityRule(controller)) {
            undescribed.add(controller.getSimpleName() + " carries a security rule on the class");
        }

        for (Method handler : handlers(controller)) {
            for (Annotation annotation : handler.getDeclaredAnnotations()) {
                Class<? extends Annotation> type = annotation.annotationType();
                String where = handler.getName() + "() carries @" + type.getSimpleName();

                if (SECURITY_RULES.contains(type)) {
                    undescribed.add(where + " directly; wrap it in an annotation with an @AccessDescription");
                } else if (isSecurityRule(type) && !type.isAnnotationPresent(AccessDescription.class)) {
                    undescribed.add(where + ", which has no @AccessDescription");
                }
            }
        }
        return undescribed;
    }

    private static boolean isSecurityRule(Class<?> element) {
        MergedAnnotations annotations = MergedAnnotations.from(element);

        return SECURITY_RULES.stream().anyMatch(annotations::isPresent);
    }

    private static List<String> unresolvedPlaceholders(Class<? extends Annotation> type) {
        Set<String> attributes = new LinkedHashSet<>();
        Arrays.stream(type.getDeclaredMethods()).map(Method::getName).forEach(attributes::add);

        List<String> unresolved = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(type.getAnnotation(AccessDescription.class).value());
        while (placeholder.find()) {
            if (!attributes.contains(placeholder.group(1))) {
                unresolved.add(placeholder.group(1));
            }
        }
        return unresolved;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @PreAuthorize("hasRole('ADMIN')")
    private @interface Undescribed {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @AccessDescription("Requires the {roles} role.")
    @PreAuthorize("hasAnyRole({value})")
    private @interface MisspelledPlaceholder {

        UserRole[] value();
    }

    // Warning: none of these may carry @Controller, or the application's component scan would register them
    private static final class InlineRule {

        @GetMapping("/inline")
        @PreAuthorize("hasRole('ADMIN')")
        void handle() {
        }
    }

    private static final class UndescribedRule {

        @GetMapping("/undescribed")
        @Undescribed
        void handle() {
        }
    }

    @AdminOnly
    private static final class ClassLevelRule {

        @GetMapping("/class-level")
        void handle() {
        }
    }
}
