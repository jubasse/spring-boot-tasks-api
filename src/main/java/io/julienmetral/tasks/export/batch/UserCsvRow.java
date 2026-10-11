package io.julienmetral.tasks.export.batch;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record UserCsvRow(
        UUID id,
        String email,
        String displayName,
        String status,
        @Nullable String roles,
        @Nullable Instant emailVerifiedAt,
        Instant createdAt,
        @Nullable Instant lastActiveAt) {

    static final List<String> HEADER =
            List.of(
                    "id",
                    "email",
                    "display_name",
                    "status",
                    "roles",
                    "email_verified_at",
                    "created_at",
                    "last_active_at");

    List<@Nullable Object> values() {
        return Arrays.asList(
                id, email, displayName, status, roles, emailVerifiedAt, createdAt, lastActiveAt);
    }
}
