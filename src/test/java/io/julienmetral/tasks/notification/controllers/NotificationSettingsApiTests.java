package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationSettingsApiTests {

    private static final String SETTINGS = "/api/v1/users/{id}/notification-settings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void getReturnsDefaultsForUserWithoutSettings() throws Exception {
        User user = createUser(UserRole.USER);

        expectFlags(
                mockMvc.perform(get(SETTINGS, user.getId()).with(as(user, UserRole.USER)))
                        .andExpect(status().isOk()),
                true, true, true, true
        );
    }

    @Test
    void getDoesNotCreateARow() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get(SETTINGS, user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isOk());

        assertThat(rowCount(user)).isZero();
    }

    @Test
    void getResponseContainsOnlyTheFourFlags() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get(SETTINGS, user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    void putStoresAndReturnsValues() throws Exception {
        User user = createUser(UserRole.USER);

        expectFlags(
                putSettings(user, as(user, UserRole.USER), body(false, true, false, true))
                        .andExpect(status().isOk()),
                false, true, false, true
        );

        Map<String, Object> row = row(user);
        assertThat(row.get("task_assigned")).isEqualTo(false);
        assertThat(row.get("task_unassigned")).isEqualTo(true);
        assertThat(row.get("task_cancelled")).isEqualTo(false);
        assertThat(row.get("task_deleted")).isEqualTo(true);
        assertThat(row.get("updated_at")).isNotNull();

        expectFlags(
                mockMvc.perform(get(SETTINGS, user.getId()).with(as(user, UserRole.USER)))
                        .andExpect(status().isOk()),
                false, true, false, true
        );
    }

    @Test
    void secondPutUpdatesTheSameRow() throws Exception {
        User user = createUser(UserRole.USER);

        putSettings(user, as(user, UserRole.USER), body(false, false, false, false))
                .andExpect(status().isOk());
        Timestamp firstUpdate = (Timestamp) row(user).get("updated_at");

        expectFlags(
                putSettings(user, as(user, UserRole.USER), body(true, false, true, false))
                        .andExpect(status().isOk()),
                true, false, true, false
        );

        assertThat(rowCount(user)).isOne();
        Map<String, Object> row = row(user);
        assertThat(row.get("task_assigned")).isEqualTo(true);
        assertThat(row.get("task_unassigned")).isEqualTo(false);
        assertThat(row.get("task_cancelled")).isEqualTo(true);
        assertThat(row.get("task_deleted")).isEqualTo(false);
        assertThat((Timestamp) row.get("updated_at")).isAfterOrEqualTo(firstUpdate);
    }

    // The tests below seed the row through JDBC, so they cover reading and updating an existing row
    // independently of the insert bug above.

    @Test
    void getReturnsStoredValues() throws Exception {
        User user = createUser(UserRole.USER);
        insertRow(user, false, true, false, true);

        expectFlags(
                mockMvc.perform(get(SETTINGS, user.getId()).with(as(user, UserRole.USER)))
                        .andExpect(status().isOk()),
                false, true, false, true
        );
    }

    @Test
    void putUpdatesAnExistingRowInPlace() throws Exception {
        User user = createUser(UserRole.USER);
        insertRow(user, false, false, false, false);
        Timestamp seededAt = (Timestamp) row(user).get("updated_at");

        expectFlags(
                putSettings(user, as(user, UserRole.USER), body(true, false, true, false))
                        .andExpect(status().isOk()),
                true, false, true, false
        );

        assertThat(rowCount(user)).isOne();
        Map<String, Object> row = row(user);
        assertThat(row.get("task_assigned")).isEqualTo(true);
        assertThat(row.get("task_unassigned")).isEqualTo(false);
        assertThat(row.get("task_cancelled")).isEqualTo(true);
        assertThat(row.get("task_deleted")).isEqualTo(false);
        assertThat((Timestamp) row.get("updated_at")).isAfter(seededAt);

        expectFlags(
                putSettings(user, as(user, UserRole.USER), body(false, true, false, true))
                        .andExpect(status().isOk()),
                false, true, false, true
        );
        assertThat(rowCount(user)).isOne();
    }

    @Test
    void adminCanUpdateAnotherUsersExistingSettings() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);
        insertRow(other, true, true, true, true);

        expectFlags(
                putSettings(other, as(admin, UserRole.ADMIN), body(false, true, true, false))
                        .andExpect(status().isOk()),
                false, true, true, false
        );

        assertThat(rowCount(other)).isOne();
        assertThat(rowCount(admin)).isZero();
    }

    @Test
    void userCannotUpdateAnotherUsersExistingSettings() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        insertRow(other, true, true, true, true);

        putSettings(other, as(user, UserRole.USER), body(false, false, false, false))
                .andExpect(status().isForbidden());

        assertThat(row(other).get("task_assigned")).isEqualTo(true);
    }

    @Test
    void userCannotReadAnotherUsersSettings() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        mockMvc.perform(get(SETTINGS, other.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCannotUpdateAnotherUsersSettings() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        putSettings(other, as(user, UserRole.USER), body(false, false, false, false))
                .andExpect(status().isForbidden());

        assertThat(rowCount(other)).isZero();
    }

    @Test
    void adminCanReadAnotherUsersSettings() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        expectFlags(
                mockMvc.perform(get(SETTINGS, other.getId()).with(as(admin, UserRole.ADMIN)))
                        .andExpect(status().isOk()),
                true, true, true, true
        );
    }

    @Test
    void adminCanUpdateAnotherUsersSettings() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        expectFlags(
                putSettings(other, as(admin, UserRole.ADMIN), body(false, true, true, false))
                        .andExpect(status().isOk()),
                false, true, true, false
        );

        assertThat(rowCount(other)).isOne();
        assertThat(rowCount(admin)).isZero();
    }

    @Test
    void getWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get(SETTINGS, user.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        put(SETTINGS, user.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(false, false, false, false))
                )
                .andExpect(status().isUnauthorized());

        assertThat(rowCount(user)).isZero();
    }

    @Test
    void getForUnknownUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get(SETTINGS, UUID.randomUUID()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    @Test
    void putForUnknownUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(
                        put(SETTINGS, unknown)
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(false, false, false, false))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        assertThat(rowCount(unknown)).isZero();
    }

    @Test
    void getForSoftDeletedUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createUser(UserRole.USER);
        softDelete(deleted);

        mockMvc.perform(get(SETTINGS, deleted.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    @Test
    void putForSoftDeletedUserReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createUser(UserRole.USER);
        softDelete(deleted);

        putSettings(deleted, as(admin, UserRole.ADMIN), body(false, false, false, false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));

        assertThat(rowCount(deleted)).isZero();
    }

    @Test
    void getForSoftDeletedUserWithStoredSettingsReturnsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createUser(UserRole.USER);
        insertRow(deleted, false, false, false, false);
        softDelete(deleted);

        mockMvc.perform(get(SETTINGS, deleted.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void putWithMissingFieldReturnsBadRequest() throws Exception {
        User user = createUser(UserRole.USER);

        putSettings(
                user,
                as(user, UserRole.USER),
                """
                        {"taskAssigned": false, "taskUnassigned": false, "taskCancelled": false}
                        """
        )
                .andExpect(status().isBadRequest());

        assertThat(rowCount(user)).isZero();
    }

    @Test
    void putWithNullFieldReturnsBadRequest() throws Exception {
        User user = createUser(UserRole.USER);

        putSettings(
                user,
                as(user, UserRole.USER),
                """
                        {"taskAssigned": null, "taskUnassigned": false, "taskCancelled": false, "taskDeleted": false}
                        """
        )
                .andExpect(status().isBadRequest());

        assertThat(rowCount(user)).isZero();
    }

    @Test
    void putWithEmptyBodyObjectReturnsBadRequest() throws Exception {
        User user = createUser(UserRole.USER);

        putSettings(user, as(user, UserRole.USER), "{}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWithInvalidUuidReturnsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get(SETTINGS, "not-a-uuid").with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putWithInvalidUuidReturnsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(
                        put(SETTINGS, "not-a-uuid")
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(false, false, false, false))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void putWithMalformedJsonReturnsBadRequest() throws Exception {
        User user = createUser(UserRole.USER);

        putSettings(user, as(user, UserRole.USER), "{\"taskAssigned\": ")
                .andExpect(status().isBadRequest());

        assertThat(rowCount(user)).isZero();
    }

    private ResultActions putSettings(User target, RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(
                put(SETTINGS, target.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    private static void expectFlags(
            ResultActions result,
            boolean taskAssigned,
            boolean taskUnassigned,
            boolean taskCancelled,
            boolean taskDeleted
    ) throws Exception {
        result
                .andExpect(jsonPath("$.taskAssigned").value(taskAssigned))
                .andExpect(jsonPath("$.taskUnassigned").value(taskUnassigned))
                .andExpect(jsonPath("$.taskCancelled").value(taskCancelled))
                .andExpect(jsonPath("$.taskDeleted").value(taskDeleted));
    }

    private static String body(
            boolean taskAssigned,
            boolean taskUnassigned,
            boolean taskCancelled,
            boolean taskDeleted
    ) {
        return """
                {"taskAssigned": %s, "taskUnassigned": %s, "taskCancelled": %s, "taskDeleted": %s}
                """.formatted(taskAssigned, taskUnassigned, taskCancelled, taskDeleted);
    }

    private int rowCount(User user) {
        return rowCount(user.getId());
    }

    private int rowCount(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notification_settings where user_id = ?",
                Integer.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private Map<String, Object> row(User user) {
        return jdbcTemplate.queryForMap(
                "select * from notification_settings where user_id = ?",
                user.getId()
        );
    }

    private void insertRow(
            User user,
            boolean taskAssigned,
            boolean taskUnassigned,
            boolean taskCancelled,
            boolean taskDeleted
    ) {
        jdbcTemplate.update(
                "insert into notification_settings "
                        + "(user_id, task_assigned, task_unassigned, task_cancelled, task_deleted, updated_at) "
                        + "values (?, ?, ?, ?, ?, now() - interval '1 hour')",
                user.getId(), taskAssigned, taskUnassigned, taskCancelled, taskDeleted
        );
    }

    private void softDelete(User user) {
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
        userRepository.flush();

        Timestamp deletedAt = jdbcTemplate.queryForObject(
                "select deleted_at from users where id = ?",
                Timestamp.class,
                user.getId()
        );
        assertThat(deletedAt).isNotNull();
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail(uniqueEmail());
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setDisplayName("Test " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
