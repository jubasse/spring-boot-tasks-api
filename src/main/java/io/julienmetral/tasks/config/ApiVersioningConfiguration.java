package io.julienmetral.tasks.config;

import io.julienmetral.tasks.TasksApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Native API versioning (Spring Framework 7): the version is the {@code v1} of {@code /api/v1/...}. Controllers declare
 * their paths without it ({@code /tasks}), and this class puts {@code /api/v{version}} in front of every controller of
 * the application, not of springdoc's. A new version of one operation is another method mapped with
 * {@code version = "2"}, once "2" is a supported version; the operations it does not change keep answering both.
 */
@Configuration
public class ApiVersioningConfiguration implements WebMvcConfigurer {

    static final String API_PATH = "/api/v{version}";

    // Mappings without a version attribute answer it
    static final String CURRENT_VERSION = "1";

    /** The path prefix of the current version, for URLs the API hands out, such as identicon links. */
    public static final String CURRENT_PATH = "/api/v" + CURRENT_VERSION;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PATH, HandlerTypePredicate.forBasePackageClass(TasksApplication.class));
    }

    /**
     * The version is read from the second path segment of the API only: the path segment resolver never yields, so
     * without the predicate {@code /v3/api-docs} and {@code /livez} would answer 400. A version nobody supports, such
     * as {@code /api/v2/...} today, or another spelling of one ({@link PathVersionParser}), answers 400 as well.
     * <p>
     * To retire a version later, give the deprecation handler its dates: {@code configureVersion("1")}, then
     * {@code setDeprecationDate} and {@code setSunsetDate}, which answer the {@code Deprecation} (RFC 9745) and
     * {@code Sunset} (RFC 8594) headers.
     */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        PathVersionParser parser = new PathVersionParser();

        configurer
                .usePathSegment(1, path -> path.pathWithinApplication().value().startsWith("/api/"))
                .setVersionParser(parser)
                .setVersionRequired(false)
                .addSupportedVersions(CURRENT_VERSION)
                // The same parser, or the versions it configures would never equal the ones requests carry
                .setDeprecationHandler(new StandardApiVersionDeprecationHandler(parser));
    }
}
