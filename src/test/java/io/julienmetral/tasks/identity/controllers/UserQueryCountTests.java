package io.julienmetral.tasks.identity.controllers;

import com.jayway.jsonpath.JsonPath;
import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.julienmetral.tasks.support.ProfilePhotos.givePendingPhoto;
import static io.julienmetral.tasks.support.ProfilePhotos.givePhoto;
import static io.julienmetral.tasks.support.SqlStatementCounter.statementsDuring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserQueryCountTests {

    // Measured with open-in-view off: the account with its profile and both photos, then its roles
    private static final int USER_STATEMENTS = 2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Response(String body, List<String> statements) {
    }

    @Test
    void readingAUserRunsTheSameNumberOfQueriesWhateverItsRolesAndPhotos() throws Exception {
        User admin = createUser(EnumSet.of(UserRole.USER, UserRole.ADMIN));
        User plain = createUser(EnumSet.of(UserRole.USER));
        User withPhotos = createUser(EnumSet.of(UserRole.USER, UserRole.ADMIN));
        givePhoto(jdbcTemplate, withPhotos.getId());
        givePendingPhoto(jdbcTemplate, withPhotos.getId());

        Response plainUser = read(admin, plain);
        Response userWithPhotos = read(admin, withPhotos);

        assertThat(JsonPath.<String>read(userWithPhotos.body(), "$.avatarUrl")).doesNotContain("/identicons/");
        assertThat(JsonPath.<Boolean>read(userWithPhotos.body(), "$.avatarPending")).isTrue();
        assertThat(JsonPath.<List<String>>read(userWithPhotos.body(), "$.roles")).hasSize(2);
        assertThat(userWithPhotos.statements())
                .hasSameSizeAs(plainUser.statements())
                .hasSizeLessThanOrEqualTo(USER_STATEMENTS);
    }

    private Response read(User admin, User target) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        MockHttpServletRequestBuilder request = get("/api/v1/users/{id}", target.getId())
                .with(jwt()
                        .jwt(token -> token.claim("uid", admin.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_" + UserRole.ADMIN.name())));

        List<String> statements = statementsDuring(() -> body.set(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()));

        return new Response(body.get(), statements);
    }

    private User createUser(Set<UserRole> roles) {
        User user = new User();

        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Query count " + UUID.randomUUID().toString().substring(0, 4));
        user.setRoles(EnumSet.copyOf(roles));

        return userRepository.saveAndFlush(user);
    }
}
