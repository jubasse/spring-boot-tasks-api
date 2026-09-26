package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The retention run looks at every user in the shared database, so the clock is pinned far in the future and the
 * dates of this class's users are set relative to it with JDBC. Tests assert on their own users only, never on
 * the counts of the report.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class, UserRetentionTests.RetentionClock.class})
@SpringBootTest
@AutoConfigureMockMvc
class UserRetentionTests {

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

    private static final String PASSWORD = "password123";

    private static final long LOCK_KEY = (long) ReflectionTestUtils.getField(UserRetentionQueries.class, "LOCK_KEY");

    @TestConfiguration(proxyBeanMethods = false)
    static class RetentionClock {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock(NOW);
        }
    }

    static final class SettableClock extends Clock {

        private volatile Instant instant;

        SettableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }

    @Autowired
    private UserRetentionService retentionService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SettableClock clock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Mailpit mailpit;

    @BeforeEach
    void resetClockAndSilenceOtherUsers() {
        clock.set(NOW);

        // Users left by other test classes are all "inactive" in 2100: without this, every run would email each of
        // them, flooding the mail queue and Mailpit. Warned at NOW, they are neither warned again nor deleted.
        jdbcTemplate.update(
                "UPDATE users SET inactivity_warned_at = ? WHERE deleted_at IS NULL AND inactivity_warned_at IS NULL",
                Timestamp.from(NOW)
        );
    }

    // Anonymization

    @Test
    void userDeletedMoreThanThirtyDaysAgoIsAnonymized() {
        User user = createUser(UserRole.USER);
        setDate(user, "last_login_at", NOW.minus(Duration.ofDays(40)));
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(40)));
        userService.delete(user.getId());
        setDate(user, "deleted_at", NOW.minus(Duration.ofDays(31)));

        retentionService.apply();

        Map<String, Object> row = userRow(user);
        assertThat(row.get("email")).isEqualTo("deleted-" + user.getId() + "@anonymized.invalid");
        assertThat(row.get("display_name")).isEqualTo("Deleted user");
        assertThat(row.get("password_hash")).isEqualTo("!");
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(row.get("last_login_at")).isNull();
        assertThat(row.get("last_active_at")).isNull();
        assertThat(row.get("inactivity_warned_at")).isNull();
        assertThat(instant(row.get("anonymized_at"))).isEqualTo(NOW);
        assertThat(instant(row.get("updated_at"))).isEqualTo(NOW);
        assertThat(instant(row.get("deleted_at"))).isEqualTo(NOW.minus(Duration.ofDays(31)));
    }

    @Test
    void anonymizationDeletesNotificationSettingsAndAllTokens() throws Exception {
        String email = uniqueEmail();
        User user = userService.create(email, PASSWORD, "Token holder");
        userService.verifyEmail(user.getId());
        login(email).andExpect(status().isOk());
        requestPasswordReset(email);
        updateNotificationSettings(user);

        assertThat(rowsOf(user, "refresh_tokens")).isPositive();
        assertThat(rowsOf(user, "email_verification_tokens")).isPositive();
        assertThat(rowsOf(user, "password_reset_tokens")).isPositive();
        assertThat(rowsOf(user, "notification_settings")).isOne();

        userService.delete(user.getId());
        setDate(user, "deleted_at", NOW.minus(Duration.ofDays(31)));

        retentionService.apply();

        assertThat(rowsOf(user, "refresh_tokens")).isZero();
        assertThat(rowsOf(user, "email_verification_tokens")).isZero();
        assertThat(rowsOf(user, "password_reset_tokens")).isZero();
        assertThat(rowsOf(user, "notification_settings")).isZero();
    }

    @Test
    void tasksCommentsAndHistoryOfAnAnonymizedUserStillLoadAndShowADeletedUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User assignee = createUser(UserRole.USER);
        UUID taskId = createTask(admin, assignee);
        mockMvc.perform(post("/api/v1/tasks/{id}/comments", taskId)
                        .with(as(assignee, UserRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Started on it\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskId)
                        .with(as(assignee, UserRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        userService.delete(assignee.getId());
        setDate(assignee, "deleted_at", NOW.minus(Duration.ofDays(31)));

        retentionService.apply();

        assertThat(instant(userRow(assignee).get("anonymized_at"))).isEqualTo(NOW);

        JsonNode task = getJson(admin, "/api/v1/tasks/" + taskId);
        assertIsDeletedUser(task.path("assignedTo"), assignee);

        JsonNode comments = getJson(admin, "/api/v1/tasks/" + taskId + "/comments").path("content");
        assertThat(comments).hasSize(1);
        assertIsDeletedUser(comments.get(0).path("author"), assignee);

        List<JsonNode> actions = new ArrayList<>();
        getJson(admin, "/api/v1/tasks/" + taskId + "/events").path("content").forEach(event -> {
            if (event.path("actor").path("id").asString().equals(assignee.getId().toString())) {
                actions.add(event);
            }
        });
        assertThat(actions).isNotEmpty();
        actions.forEach(event -> assertIsDeletedUser(event.path("actor"), assignee));
    }

    @Test
    void anonymizedUserIsNotTouchedAgainByALaterRun() {
        User user = createUser(UserRole.USER);
        userService.delete(user.getId());
        setDate(user, "deleted_at", NOW.minus(Duration.ofDays(31)));

        retentionService.apply();
        Map<String, Object> afterFirstRun = userRow(user);

        clock.set(NOW.plus(Duration.ofDays(1)));
        retentionService.apply();

        assertThat(userRow(user)).isEqualTo(afterFirstRun);
        assertThat(instant(afterFirstRun.get("anonymized_at"))).isEqualTo(NOW);
    }

    @Test
    void userDeletedLessThanThirtyDaysAgoKeepsTheirPersonalData() {
        User user = createUser(UserRole.USER);
        userService.delete(user.getId());
        setDate(user, "deleted_at", NOW.minus(Duration.ofDays(29)));

        retentionService.apply();

        Map<String, Object> row = userRow(user);
        assertThat(row.get("email")).isEqualTo(user.getEmail());
        assertThat(row.get("display_name")).isEqualTo(user.getDisplayName());
        assertThat(row.get("password_hash")).isEqualTo(user.getPasswordHash());
        assertThat(row.get("anonymized_at")).isNull();
    }

    @Test
    void originalEmailCanSignUpAgainOnceTheDeletedUserIsAnonymized() throws Exception {
        String email = uniqueEmail();
        User user = userService.create(email, PASSWORD, "Returning user");
        userService.delete(user.getId());
        setDate(user, "deleted_at", NOW.minus(Duration.ofDays(31)));

        signUp(email).andExpect(status().isConflict());

        retentionService.apply();

        signUp(email).andExpect(status().isCreated());
        User newAccount = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(newAccount.getId()).isNotEqualTo(user.getId());
    }

    // Inactivity warning

    @Test
    void accountInactiveForMoreThanTwoYearsIsWarnedByEmail() {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(731)));

        retentionService.apply();

        assertThat(warnedAt(user)).isEqualTo(NOW);
        assertThat(mailpit.latestTextTo(user.getEmail()))
                .startsWith("Hello " + user.getDisplayName() + ",")
                .contains("deleted on 2100-01-31.");
        assertThat(mailpit.countTo(user.getEmail())).isOne();
        assertThat(deletedAt(user)).isNull();
    }

    @Test
    void lastLoginIsTheActivityOfAnAccountWithoutLastActiveAt() {
        User oldLogin = createUser(UserRole.USER);
        setDate(oldLogin, "last_login_at", NOW.minus(Duration.ofDays(731)));
        User recentLogin = createUser(UserRole.USER);
        setDate(recentLogin, "last_login_at", NOW.minus(Duration.ofDays(10)));
        setDate(recentLogin, "created_at", NOW.minus(Duration.ofDays(3000)));

        retentionService.apply();

        assertThat(warnedAt(oldLogin)).isEqualTo(NOW);
        assertThat(warnedAt(recentLogin)).isNull();
    }

    @Test
    void creationIsTheActivityOfAnAccountThatNeverLoggedIn() {
        User oldAccount = createUser(UserRole.USER);
        setDate(oldAccount, "created_at", NOW.minus(Duration.ofDays(731)));
        User recentAccount = createUser(UserRole.USER);
        setDate(recentAccount, "created_at", NOW.minus(Duration.ofDays(10)));

        retentionService.apply();

        assertThat(warnedAt(oldAccount)).isEqualTo(NOW);
        assertThat(warnedAt(recentAccount)).isNull();
    }

    @Test
    void lastActiveAtTakesPrecedenceOverAnOlderLastLogin() {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(10)));
        setDate(user, "last_login_at", NOW.minus(Duration.ofDays(1000)));
        setDate(user, "created_at", NOW.minus(Duration.ofDays(3000)));

        retentionService.apply();

        assertThat(warnedAt(user)).isNull();
    }

    @Test
    void adminIsNeverWarned() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        setDate(admin, "created_at", NOW.minus(Duration.ofDays(3000)));
        setDate(admin, "last_login_at", NOW.minus(Duration.ofDays(3000)));

        retentionService.apply();

        assertThat(warnedAt(admin)).isNull();
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(admin.getEmail())).isZero();
    }

    @Test
    void alreadyWarnedAccountIsNotWarnedTwice() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));

        retentionService.apply();
        mailpit.latestTextTo(user.getEmail());

        clock.set(NOW.plus(Duration.ofDays(1)));
        retentionService.apply();

        assertThat(warnedAt(user)).isEqualTo(NOW);
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(user.getEmail())).isOne();
    }

    @Test
    void accountWarnedEarlierIsNotWarnedAgain() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));
        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(10)));

        retentionService.apply();

        assertThat(warnedAt(user)).isEqualTo(NOW.minus(Duration.ofDays(10)));
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(user.getEmail())).isZero();
    }

    @Test
    void recentlyActiveAccountIsUntouched() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(1)));
        setDate(user, "created_at", NOW.minus(Duration.ofDays(3000)));

        retentionService.apply();

        assertThat(warnedAt(user)).isNull();
        assertThat(deletedAt(user)).isNull();
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(user.getEmail())).isZero();
    }

    // Activity

    @Test
    void loginRecordsActivityAndClearsTheWarning() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", Instant.parse("2020-01-01T00:00:00Z"));
        setDate(user, "inactivity_warned_at", Instant.parse("2022-01-01T00:00:00Z"));

        login(user.getEmail()).andExpect(status().isOk());

        assertThat(instant(userRow(user).get("last_active_at"))).isEqualTo(NOW);
        assertThat(warnedAt(user)).isNull();
    }

    @Test
    void refreshRecordsActivityAndClearsTheWarning() throws Exception {
        User user = createUser(UserRole.USER);
        String refreshToken = refreshTokenOf(login(user.getEmail()).andExpect(status().isOk()));
        setDate(user, "last_active_at", Instant.parse("2020-01-01T00:00:00Z"));
        setDate(user, "inactivity_warned_at", Instant.parse("2022-01-01T00:00:00Z"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        assertThat(instant(userRow(user).get("last_active_at"))).isEqualTo(NOW);
        assertThat(warnedAt(user)).isNull();
    }

    // Deletion for inactivity

    @Test
    void accountWarnedMoreThanThirtyDaysAgoAndStillInactiveIsDeletedAndSignedOut() throws Exception {
        User user = createUser(UserRole.USER);
        login(user.getEmail()).andExpect(status().isOk());
        login(user.getEmail()).andExpect(status().isOk());
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));
        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(31)));

        retentionService.apply();

        assertThat(deletedAt(user)).isNotNull();
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(unrevokedRefreshTokensOf(user)).isZero();
        assertThat(rowsOf(user, "refresh_tokens")).isEqualTo(2);
        assertThat(userRow(user).get("anonymized_at")).isNull();
    }

    @Test
    void accountWarnedLessThanThirtyDaysAgoIsKept() {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));
        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(29)));

        retentionService.apply();

        assertThat(deletedAt(user)).isNull();
        assertThat(warnedAt(user)).isEqualTo(NOW.minus(Duration.ofDays(29)));
    }

    @Test
    void accountThatLoggedInAfterTheWarningIsKept() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));
        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(31)));

        login(user.getEmail()).andExpect(status().isOk());
        assertThat(warnedAt(user)).isNull();

        retentionService.apply();

        assertThat(deletedAt(user)).isNull();
        assertThat(userRepository.findById(user.getId())).isPresent();
        // The login time comes from the same clock as the run, so the account is not warned again
        assertThat(warnedAt(user)).isNull();
    }

    @Test
    void disabledAccountIsWarnedWithoutAnEmailAndDeletedAfterTheNotice() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(731)));
        userService.disable(user.getId());

        retentionService.apply();

        assertThat(warnedAt(user)).isEqualTo(NOW);
        waitForAsyncDispatch();
        assertThat(mailpit.countTo(user.getEmail())).isZero();

        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(31)));
        retentionService.apply();

        assertThat(deletedAt(user)).isNotNull();
    }

    @Test
    void userPromotedToAdminAfterTheWarningIsNotDeleted() {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));
        setDate(user, "inactivity_warned_at", NOW.minus(Duration.ofDays(31)));
        userService.addRole(user.getId(), UserRole.ADMIN);

        retentionService.apply();

        assertThat(deletedAt(user)).isNull();
    }

    // Locking

    @Test
    void runIsSkippedWhileAnotherInstanceHoldsTheLock() throws Exception {
        User user = createUser(UserRole.USER);
        setDate(user, "last_active_at", NOW.minus(Duration.ofDays(800)));

        try (Connection otherInstance = dataSource.getConnection()) {
            executeWithLockKey(otherInstance, "SELECT pg_advisory_lock(?)");
            try {
                assertThat(retentionService.apply()).isEqualTo(UserRetentionReport.skippedRun());
            } finally {
                executeWithLockKey(otherInstance, "SELECT pg_advisory_unlock(?)");
            }
        }

        assertThat(warnedAt(user)).isNull();

        assertThat(retentionService.apply().skipped()).isFalse();
        assertThat(warnedAt(user)).isEqualTo(NOW);
    }

    private User createUser(UserRole role) {
        User user = new User();
        user.setEmail(uniqueEmail());
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Retained " + role + " " + UUID.randomUUID().toString().substring(0, 6));
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private UUID createTask(User admin, User assignee) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tasks")
                        .with(as(admin, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "RET-%s", "title": "Retention", "priority": "HIGH", "assignedTo": "%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), assignee.getId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(jsonMapper.readTree(body).get("id").asString());
    }

    private JsonNode getJson(User caller, String uri) throws Exception {
        String body = mockMvc.perform(get(uri).with(as(caller, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body);
    }

    private static void assertIsDeletedUser(JsonNode preview, User user) {
        assertThat(preview.path("id").asString()).isEqualTo(user.getId().toString());
        assertThat(preview.path("displayName").asString()).isEqualTo("Deleted user");
        assertThat(preview.path("status").asString()).isEqualTo("DELETED");
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))));
    }

    private String refreshTokenOf(ResultActions login) throws Exception {
        return jsonMapper.readTree(login.andReturn().getResponse().getContentAsString())
                .get("refreshToken")
                .asString();
    }

    private ResultActions signUp(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "displayName", "Signed up again"))));
    }

    private void requestPasswordReset(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
    }

    private void updateNotificationSettings(User user) throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/notification-settings", user.getId())
                        .with(as(user, UserRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskAssigned": false, "taskUnassigned": true, "taskCancelled": true,
                                 "taskDeleted": true, "taskCommented": true, "taskMentioned": true,
                                 "taskDueSoon": true, "taskOverdue": true}
                                """))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private void setDate(User user, String column, Instant value) {
        jdbcTemplate.update("UPDATE users SET " + column + " = ? WHERE id = ?", Timestamp.from(value), user.getId());
    }

    private Map<String, Object> userRow(User user) {
        return jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", user.getId());
    }

    private Instant warnedAt(User user) {
        return instant(userRow(user).get("inactivity_warned_at"));
    }

    private Instant deletedAt(User user) {
        return instant(userRow(user).get("deleted_at"));
    }

    private int rowsOf(User user, String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                user.getId());
    }

    private int unrevokedRefreshTokensOf(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                user.getId()
        );
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private static String uniqueEmail() {
        return "retention-" + UUID.randomUUID() + "@example.com";
    }

    // Mail is dispatched asynchronously after commit: "nothing sent" can only be checked after a grace period
    private static void waitForAsyncDispatch() throws InterruptedException {
        Thread.sleep(Duration.ofMillis(800));
    }

    private static void executeWithLockKey(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        }
    }
}
