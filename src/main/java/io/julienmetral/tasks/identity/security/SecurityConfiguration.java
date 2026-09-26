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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

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
                .authorizeHttpRequests(auth ->
                        auth
                                // Errors are rendered by an internal dispatch to /error, after the request itself was
                                // authorized. Securing that dispatch turned every 400 of a public endpoint (malformed
                                // JSON on login or sign-up, an invalid identicon id) into a 401.
                                .dispatcherTypeMatchers(DispatcherType.ERROR)
                                .permitAll()
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/users"
                                )
                                .permitAll()

                                // Login, token refresh and logout carry their own credentials;
                                // email verification carries the token received by email
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/refresh",
                                        "/api/v1/auth/logout",
                                        "/api/v1/auth/verify-email",
                                        "/api/v1/auth/password-reset/request",
                                        "/api/v1/auth/password-reset/confirm"
                                )
                                .permitAll()
                                // Everything on the management port is public, that port being private by
                                // deployment. Matching the endpoints only left its error page (404, 406, 500)
                                // behind authentication, so every error there answered 401.
                                .requestMatchers(onManagementPort(environment))
                                .permitAll()
                                // Identicons are images loaded by <img> tags, which send no token
                                .requestMatchers(HttpMethod.GET, "/api/v1/identicons/*")
                                .permitAll()
                                // Tasks are reserved to enabled users with a verified email
                                .requestMatchers("/api/v1/tasks/**")
                                .access(activeUserAuthorizationManager)

                                .anyRequest()
                                .authenticated()
                )
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
