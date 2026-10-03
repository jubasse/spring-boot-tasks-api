package io.julienmetral.tasks.support;

import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.ActiveUserAuthorizationManager;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.identity.security.JwtConfiguration;
import io.julienmetral.tasks.identity.security.SecurityConfiguration;
import io.julienmetral.tasks.identity.security.UserAuthorization;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.identity.services.AuthService;
import io.julienmetral.tasks.identity.services.AvatarService;
import io.julienmetral.tasks.identity.services.DatabaseUserDetailsService;
import io.julienmetral.tasks.identity.services.EmailVerificationService;
import io.julienmetral.tasks.identity.services.IdenticonGenerator;
import io.julienmetral.tasks.identity.services.PasswordResetService;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.notification.services.NotificationSettingsService;
import io.julienmetral.tasks.notification.services.WebhookEndpointService;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
import io.julienmetral.tasks.task.security.TaskAttachmentAuthorization;
import io.julienmetral.tasks.task.security.TaskAuthorization;
import io.julienmetral.tasks.task.security.TaskCommentAuthorization;
import io.julienmetral.tasks.task.services.TaskAttachmentService;
import io.julienmetral.tasks.task.services.TaskCommentService;
import io.julienmetral.tasks.task.services.TaskEventService;
import io.julienmetral.tasks.task.services.TaskService;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every controller and {@code ApiExceptionHandler} behind the real security: the filter chain, JWT decoding, method
 * security and {@link ActiveUserAuthorizationManager}. No database, broker or container: services, repositories and
 * the ownership beans that query the database are mocks, while the pure security components run for real.
 * <p>
 * Task and export endpoints first read the caller's account state through {@link UserStatusLookup} and the
 * {@link UserRepository} mock, so a test calling them stubs it with {@link WebCallers#everyAccountIsActive}. The slice
 * enables no caching, so every request reads it.
 * <p>
 * Warning: this annotation loads all controllers on purpose, so that every web test shares one cached context. Naming
 * the controllers of a class, or adding a mock or an import to a single class, creates another context, which counts
 * against the context cache cap of {@code spring.properties}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@WebMvcTest
@Import({
        SecurityConfiguration.class,
        JwtConfiguration.class,
        CurrentUser.class,
        UserAuthorization.class,
        ActiveUserAuthorizationManager.class,
        UserStatusLookup.class,
        IdenticonGenerator.class
})
@MockitoBean(types = {
        AuthService.class,
        EmailVerificationService.class,
        PasswordResetService.class,
        UserService.class,
        AvatarService.class,
        NotificationSettingsService.class,
        WebhookEndpointService.class,
        NotificationStreams.class,
        TaskService.class,
        TaskCommentService.class,
        TaskAttachmentService.class,
        TaskEventService.class,
        DataExportService.class,
        MediaUrls.class,
        RateLimiter.class,
        DatabaseUserDetailsService.class,
        UserRepository.class
})
@MockitoBean(name = "taskAuthorization", types = TaskAuthorization.class)
@MockitoBean(name = "taskAttachmentAuthorization", types = TaskAttachmentAuthorization.class)
@MockitoBean(name = "taskCommentAuthorization", types = TaskCommentAuthorization.class)
public @interface WebLayerTest {
}
