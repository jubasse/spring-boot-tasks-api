package io.julienmetral.tasks.identity.repositories;

import io.julienmetral.tasks.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The schema Liquibase leaves behind (migration 016), read from the Postgres catalog. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserProfileSchemaTests {

    // One row per single-column foreign key of the current schema
    private static final String FOREIGN_KEYS = """
            SELECT con.conname AS name,
                   src.relname AS source_table,
                   src_col.attname AS source_column,
                   ref.relname AS referenced_table,
                   ref_col.attname AS referenced_column
            FROM pg_constraint con
            JOIN pg_class src ON src.oid = con.conrelid
            JOIN pg_namespace ns ON ns.oid = src.relnamespace
            JOIN pg_class ref ON ref.oid = con.confrelid
            JOIN pg_attribute src_col ON src_col.attrelid = con.conrelid AND src_col.attnum = con.conkey[1]
            JOIN pg_attribute ref_col ON ref_col.attrelid = con.confrelid AND ref_col.attnum = con.confkey[1]
            WHERE con.contype = 'f' AND ns.nspname = current_schema()
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<Map<String, Object>> foreignKeysFrom(String table, String column) {
        return jdbcTemplate.queryForList(
                FOREIGN_KEYS + " AND src.relname = ? AND src_col.attname = ?",
                table,
                column
        );
    }

    private List<Map<String, Object>> foreignKeysTo(String table) {
        return jdbcTemplate.queryForList(FOREIGN_KEYS + " AND ref.relname = ?", table);
    }

    @Test
    void usersIdReferencesUserProfilesId() {
        assertThat(foreignKeysFrom("users", "id"))
                .extracting(fk -> fk.get("name"), fk -> fk.get("referenced_table"), fk -> fk.get("referenced_column"))
                .containsExactly(tuple("users_profileFK", "user_profiles", "id"));
    }

    @Test
    void userProfilesHasNoForeignKeyToUsers() {
        assertThat(jdbcTemplate.queryForList(
                FOREIGN_KEYS + " AND src.relname = 'user_profiles' AND ref.relname = 'users'"
        )).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "tasks, assigned_to_id, tasks_assigned_toFK",
            "tasks, created_by_id, tasks_created_byFK",
            "task_events, actor_id, task_events_actorFK",
            "task_comments, author_id, task_comments_authorFK",
            "task_comment_mentions, user_id, task_comment_mentions_userFK",
            "task_reminders, recipient_id, task_reminders_recipientFK",
            "media, uploaded_by_id, media_uploaded_byFK",
            "refresh_tokens, user_id, refresh_tokens_userFK",
            "email_verification_tokens, user_id, email_verification_tokens_userFK",
            "password_reset_tokens, user_id, password_reset_tokens_userFK"
    })
    void referenceToAUserPointsToItsProfile(String table, String column, String constraint) {
        assertThat(foreignKeysFrom(table, column))
                .extracting(fk -> fk.get("name"), fk -> fk.get("referenced_table"), fk -> fk.get("referenced_column"))
                .containsExactly(tuple(constraint, "user_profiles", "id"));
    }

    @Test
    void onlyRolesAndNotificationSettingsReferenceTheAccount() {
        // The erasure deletes the users row: any other reference to it would block the deletion
        assertThat(foreignKeysTo("users"))
                .extracting(fk -> fk.get("source_table"), fk -> fk.get("source_column"))
                .containsExactlyInAnyOrder(
                        tuple("user_roles", "user_id"),
                        tuple("notification_settings", "user_id")
                );
    }

    @Test
    void profilePhotosReferenceMediaWithTheirOwnUniqueConstraints() {
        assertThat(foreignKeysFrom("user_profiles", "avatar_media_id"))
                .extracting(fk -> fk.get("name"), fk -> fk.get("referenced_table"))
                .containsExactly(tuple("user_profiles_avatar_mediaFK", "media"));
        assertThat(foreignKeysFrom("user_profiles", "pending_avatar_media_id"))
                .extracting(fk -> fk.get("name"), fk -> fk.get("referenced_table"))
                .containsExactly(tuple("user_profiles_pending_avatar_mediaFK", "media"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND conrelid = 'user_profiles'::regclass",
                String.class
        )).containsExactlyInAnyOrder("user_profiles_avatar_media_idUQ", "user_profiles_pending_avatar_media_idUQ");
    }

    @Test
    void profileColumnsLeftTheUsersTable() {
        List<String> userColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'users'",
                String.class
        );

        assertThat(userColumns)
                .contains("id", "email", "password_hash", "enabled", "email_verified_at", "deleted_at")
                .doesNotContain("display_name", "avatar_media_id", "pending_avatar_media_id", "anonymized_at");
    }

    @Test
    void usersIdHasNoDefaultSinceItComesFromTheProfile() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'id'",
                String.class
        )).isNull();
    }
}
