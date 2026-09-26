package io.julienmetral.tasks.identity.repositories;

import com.zaxxer.hikari.HikariDataSource;
import io.julienmetral.tasks.TestcontainersConfiguration;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs migration 016 on rows written with the schema before it. The migrations run in a schema of their own, on a
 * connection outside the pool: Liquibase changes the connection's search path, which a pooled connection would keep
 * for the next test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserProfilesMigrationTests {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    // Every changeset of 001 to 015: the next one is 016-create-user-profiles
    private static final int CHANGESETS_BEFORE_USER_PROFILES = 27;

    @Autowired
    private DataSource dataSource;

    private Connection connection;

    private String schema;

    private JdbcTemplate jdbc;

    private Liquibase liquibase;

    @BeforeEach
    void migrateAFreshSchemaUpToTheMigrationBeforeUserProfiles() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        schema = "migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        connection = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        connection.createStatement().execute("CREATE SCHEMA " + schema);
        connection.setSchema(schema);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        database.setDefaultSchemaName(schema);
        database.setLiquibaseSchemaName(schema);
        liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
        liquibase.update(CHANGESETS_BEFORE_USER_PROFILES, "");

        assertThat(columnExists("users", "display_name")).isTrue();
        assertThat(tableExists("user_profiles")).isFalse();
    }

    @AfterEach
    void dropTheSchema() throws SQLException {
        try {
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        } finally {
            connection.close();
        }
    }

    @Test
    void everyUserGetsAProfileWithItsIdNamePhotoAndStatus() throws Exception {
        UUID media = insertMedia();
        UUID active = insertUser("Active", true, true, false, null, media);
        UUID unverified = insertUser("Unverified", true, false, false, null, null);
        UUID disabled = insertUser("Disabled", false, true, false, null, null);
        UUID deleted = insertUser("Deleted", true, true, true, null, null);

        liquibase.update("");

        assertThat(profile(active))
                .containsEntry("display_name", "Active")
                .containsEntry("status", "ACTIVE")
                .containsEntry("avatar_media_id", media);
        assertThat(profile(unverified)).containsEntry("status", "UNVERIFIED");
        assertThat(profile(disabled)).containsEntry("status", "DISABLED");
        assertThat(profile(deleted))
                .containsEntry("display_name", "Deleted")
                .containsEntry("status", "DELETED");
        assertThat(count("SELECT count(*) FROM users WHERE id IN (?, ?, ?, ?)", active, unverified, disabled, deleted))
                .isEqualTo(4);
    }

    @Test
    void referencesKeepTheirValueAndNowPointToTheProfile() throws Exception {
        UUID creator = insertUser("Creator", true, true, false, null, null);
        UUID task = insertTask(creator);

        liquibase.update("");

        assertThat(jdbc.queryForObject("SELECT created_by_id FROM tasks WHERE id = ?", UUID.class, task))
                .isEqualTo(creator);
        assertThat(count("SELECT count(*) FROM user_profiles WHERE id = ?", creator)).isOne();
    }

    @Test
    void alreadyAnonymizedUserLosesItsAccountRowAndKeepsItsProfile() throws Exception {
        UUID anonymized = insertUser("Deleted user", false, false, true, "now() - interval '10 days'", null);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'USER')", anonymized);
        UUID task = insertTask(anonymized);

        liquibase.update("");

        assertThat(count("SELECT count(*) FROM users WHERE id = ?", anonymized)).isZero();
        assertThat(count("SELECT count(*) FROM user_roles WHERE user_id = ?", anonymized)).isZero();
        assertThat(profile(anonymized))
                .containsEntry("display_name", "Deleted user")
                .containsEntry("status", "DELETED");
        assertThat(profile(anonymized).get("anonymized_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT created_by_id FROM tasks WHERE id = ?", UUID.class, task))
                .isEqualTo(anonymized);
    }

    @Test
    void alreadyAnonymizedUserKeepsNoPhotoReference() throws Exception {
        UUID avatar = insertMedia();
        UUID pendingAvatar = insertMedia();
        UUID anonymized = insertUser("Deleted user", false, false, true, "now() - interval '10 days'", avatar);
        jdbc.update("UPDATE users SET pending_avatar_media_id = ? WHERE id = ?", pendingAvatar, anonymized);

        liquibase.update("");

        assertThat(profile(anonymized).get("avatar_media_id")).isNull();
        assertThat(profile(anonymized).get("pending_avatar_media_id")).isNull();
    }

    private UUID insertUser(
            String displayName,
            boolean enabled,
            boolean verified,
            boolean deleted,
            String anonymizedAt,
            UUID avatar
    ) {
        return jdbc.queryForObject(
                """
                        INSERT INTO users (created_at, updated_at, display_name, email, email_verified_at, enabled,
                                           password_hash, deleted_at, avatar_media_id, anonymized_at)
                        VALUES (now(), now(), ?, ?, %s, ?, '!', %s, ?, %s)
                        RETURNING id
                        """.formatted(
                        verified ? "now()" : "NULL",
                        deleted ? "now() - interval '40 days'" : "NULL",
                        anonymizedAt == null ? "NULL" : anonymizedAt
                ),
                UUID.class,
                displayName,
                "migration-" + UUID.randomUUID() + "@example.com",
                enabled,
                avatar
        );
    }

    private UUID insertMedia() {
        return jdbc.queryForObject(
                """
                        INSERT INTO media (storage_key, usage, original_filename, content_type, size_bytes, sha256,
                                           created_at)
                        VALUES (?, 'AVATAR', 'photo.png', 'image/png', 1, repeat('0', 64), now())
                        RETURNING id
                        """,
                UUID.class,
                "avatar/" + UUID.randomUUID()
        );
    }

    private UUID insertTask(UUID creator) {
        return jdbc.queryForObject(
                """
                        INSERT INTO tasks (created_at, updated_at, priority, reference, status, title, version,
                                           created_by_id)
                        VALUES (now(), now(), 'LOW', ?, 'TO_DO', 'Migrated task', 0, ?)
                        RETURNING id
                        """,
                UUID.class,
                "MIG-" + UUID.randomUUID().toString().substring(0, 8),
                creator
        );
    }

    private Map<String, Object> profile(UUID id) {
        return jdbc.queryForMap("SELECT * FROM user_profiles WHERE id = ?", id);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT exists (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                Boolean.class,
                schema,
                table
        ));
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                        SELECT exists (SELECT 1 FROM information_schema.columns
                                       WHERE table_schema = ? AND table_name = ? AND column_name = ?)
                        """,
                Boolean.class,
                schema,
                table,
                column
        ));
    }
}
