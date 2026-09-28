package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.services.DatabaseUserDetailsService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    // The probes of the orchestrator, which sends no token; the health details stay on the management port
    private static final String[] PROBES = {"/livez", "/readyz"};

    private static final String[] API_DOCUMENTATION = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    /**
     * New hashes use Argon2id with the OWASP minimum parameters (19 MiB memory, 2 iterations, parallelism 1).
     * BCrypt stays registered only to verify existing hashes, which are upgraded to Argon2id on the next
     * successful login (see {@link DatabaseUserDetailsService#updatePassword}).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        String encodingId = "argon2id";

        return new DelegatingPasswordEncoder(
                encodingId,
                Map.of(
                        encodingId, new Argon2PasswordEncoder(16, 32, 1, 19_456, 2),
                        "bcrypt", new BCryptPasswordEncoder()
                )
        );
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(
            DatabaseUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        var provider =
                new DaoAuthenticationProvider(
                        userDetailsService
                );

        provider.setPasswordEncoder(
                passwordEncoder
        );

        provider.setUserDetailsPasswordService(
                userDetailsService
        );

        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(
            DaoAuthenticationProvider authenticationProvider
    ) {
        return new ProviderManager(
                authenticationProvider
        );
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {

        var authoritiesConverter =
                new JwtGrantedAuthoritiesConverter();

        authoritiesConverter
                .setAuthoritiesClaimName("roles");

        authoritiesConverter
                .setAuthorityPrefix("");

        var authenticationConverter =
                new JwtAuthenticationConverter();

        authenticationConverter
                .setJwtGrantedAuthoritiesConverter(
                        authoritiesConverter
                );

        return authenticationConverter;
    }

    // Public endpoints and probes ignore the Authorization header: a client that kept its expired access token got a
    // 401 from login and refresh, the very endpoints that give it a new one, and a probe would fail the same way
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        RequestMatcher withoutToken = new OrRequestMatcher(Stream.concat(
                        PublicEndpoints.ALL.stream().map(endpoint -> matcher(endpoint.method(), endpoint.pattern())),
                        Arrays.stream(PROBES).map(probe -> matcher(HttpMethod.GET, probe)))
                .toList());

        return request -> withoutToken.matches(request) ? null : resolver.resolve(request);
    }

    private static RequestMatcher matcher(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ActiveUserAuthorizationManager activeUserAuthorizationManager,
            Environment environment
    ) {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> {
                    // Errors are rendered by an internal dispatch to /error, after the request itself was authorized.
                    // Securing that dispatch turned every 400 of a public endpoint (malformed JSON on login or
                    // sign-up, an invalid identicon id) into a 401.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();

                    PublicEndpoints.ALL.forEach(endpoint ->
                            auth.requestMatchers(endpoint.method(), endpoint.pattern()).permitAll());

                    auth
                            // Everything on the management port is public, that port being private by deployment.
                            // Matching the endpoints only left its error page (404, 406, 500) behind
                            // authentication, so every error there answered 401.
                            .requestMatchers(onManagementPort(environment))
                            .permitAll()
                            // The documentation of a public API; API_DOCS_ENABLED and SWAGGER_UI_ENABLED turn it off
                            .requestMatchers(HttpMethod.GET, API_DOCUMENTATION)
                            .permitAll()
                            .requestMatchers(HttpMethod.GET, PROBES)
                            .permitAll()
                            // Tasks are reserved to enabled users with a verified email, whatever the API version
                            .requestMatchers("/api/*/tasks/**")
                            .access(activeUserAuthorizationManager)
                            .anyRequest()
                            .authenticated();
                })
                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    // local.management.port is published once the management server has started, with its actual port (also when
    // it is random). It is absent when management shares the API port, and then nothing matches.
    private static RequestMatcher onManagementPort(Environment environment) {
        return request -> String.valueOf(request.getLocalPort())
                .equals(environment.getProperty("local.management.port"));
    }
}
