package io.julienmetral.tasks.messaging;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.messaging.AvatarQueues;
import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailQueues;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import io.julienmetral.tasks.messaging.services.Outbox;
import io.julienmetral.tasks.messaging.services.OutboxRelay;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox against the real containers: rows written in the business transaction, published to RabbitMQ after
 * commit, locked with {@code SKIP LOCKED} and purged after the retention.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class OutboxTests {

    // Publishing runs asynchronously after commit: "nothing sent" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(800);

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

    // Rows inserted by the tests below are older than any other, so they come first in lockDue's order
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

    @Autowired
    private MailService mailService;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<UUID> insertedRows = new ArrayList<>();

    @AfterEach
    void deleteInsertedRows() {
        repository.deleteAllById(insertedRows);
    }

    @Test
    void emailSentInCommittedTransactionIsPublishedFromItsRowAndDelivered() {
        String email = uniqueEmail();

        inTransaction().executeWithoutResult(status ->
                mailService.send(new MailMessage(email, "Outbox", "Published after commit")));

        UUID id = rowIdSentTo(email);
        OutboxMessage row = awaitPublished(id);

        assertThat(row.getQueue()).isEqualTo(MailQueues.SEND);
        assertThat(row.getType()).isEqualTo(MailMessage.class.getName());
        assertThat(row.getPayload())
                .isEqualTo(Map.of("to", email, "subject", "Outbox", "text", "Published after commit"));
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getPublishedAt()).isAfterOrEqualTo(row.getCreatedAt());
        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Published after commit");
        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void emailSentInRolledBackTransactionLeavesNoRowAndIsNeverSent() throws InterruptedException {
        String email = uniqueEmail();

        inTransaction().executeWithoutResult(status -> {
            mailService.send(new MailMessage(email, "Rolled back", "Must not be sent"));
            status.setRollbackOnly();
        });

        assertThat(rowIdsSentTo(email)).isEmpty();
        Thread.sleep(NO_MAIL_GRACE_PERIOD);
        assertThat(mailpit.countTo(email)).isZero();
    }

    @Test
    void emailSentWithoutSurroundingTransactionWritesItsOwnRowAndIsDelivered() {
        String email = uniqueEmail();

        mailService.send(new MailMessage(email, "No transaction", "Sent in a transaction of its own"));

        awaitPublished(rowIdSentTo(email));
        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Sent in a transaction of its own");
    }

    @Test
    void enqueueOutsideATransactionIsRefusedAndWritesNothing() {
        String email = uniqueEmail();
        MailMessage message = new MailMessage(email, "Refused", "No transaction to join");

        assertThatThrownBy(() -> outbox.enqueue(MailQueues.SEND, message))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rowIdsSentTo(email)).isEmpty();
    }

    @Test
    void lockDueSkipsTheRowsLockedByAnotherTransactionAndFillsTheBatchWithTheNextOnes() throws SQLException {
        UUID oldest = insertRow(LONG_AGO, null);
        UUID second = insertRow(LONG_AGO.plusSeconds(1), null);
        UUID third = insertRow(LONG_AGO.plusSeconds(2), null);

        try (Connection otherTransaction = lockRows(oldest)) {
            List<UUID> locked = inTransaction().execute(status -> ids(repository.lockDue(inTwoDays(), 2)));

            assertThat(locked).containsExactly(second, third);
            otherTransaction.rollback();
        }
    }

    @Test
    void lockDueReturnsOnlyUnpublishedRowsThatAreDue() {
        UUID due = insertRow(LONG_AGO, null);
        UUID published = insertRow(LONG_AGO.plusSeconds(1), LONG_AGO.plusSeconds(2));
        UUID notDueYet = insertRow(LONG_AGO.plusSeconds(3), null, Instant.now().plus(Duration.ofDays(3)));

        List<UUID> locked = inTransaction().execute(status -> ids(repository.lockDue(inTwoDays(), 10)));

        assertThat(locked).contains(due).doesNotContain(published, notDueYet);
    }

    @Test
    void lockUnpublishedSkipsTheRowsLockedByAnotherTransactionAndThePublishedOnes() throws SQLException {
        UUID lockedElsewhere = insertRow(LONG_AGO, null);
        UUID free = insertRow(LONG_AGO.plusSeconds(1), null);
        UUID published = insertRow(LONG_AGO.plusSeconds(2), LONG_AGO.plusSeconds(3));

        try (Connection otherTransaction = lockRows(lockedElsewhere)) {
            List<UUID> locked = inTransaction().execute(status ->
                    ids(repository.lockUnpublished(List.of(lockedElsewhere, free, published))));

            assertThat(locked).containsExactly(free);
            otherTransaction.rollback();
        }
    }

    @Test
    void rowsLockedByThePollerStayLockedUntilItsTransactionEnds() {
        UUID id = insertRow(LONG_AGO, null);

        inTransaction().executeWithoutResult(status -> {
            assertThat(ids(repository.lockDue(inTwoDays(), 10))).contains(id);

            assertThat(inAnotherTransaction(() -> ids(repository.lockUnpublished(List.of(id))))).isEmpty();
        });

        assertThat(inAnotherTransaction(() -> ids(repository.lockUnpublished(List.of(id))))).containsExactly(id);
    }

    @Test
    void purgeDeletesOnlyTheRowsPublishedBeforeTheRetention() {
        Instant now = Instant.now();
        UUID publishedLongAgo = insertRow(now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)));
        UUID publishedRecently = insertRow(now.minus(Duration.ofDays(7)), now.minus(Duration.ofDays(6)));
        UUID neverPublished = insertRow(now.minus(Duration.ofDays(30)), null);

        relay.deletePublished();

        assertThat(repository.existsById(publishedLongAgo)).isFalse();
        assertThat(repository.existsById(publishedRecently)).isTrue();
        assertThat(repository.existsById(neverPublished)).isTrue();
    }

    @Test
    void profilePhotoUploadIsPublishedThroughTheOutboxAndProcessedByTheWorker() throws Exception {
        User user = createUser();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/users/{id}/avatar", user.getId())
                        .file(new MockMultipartFile("file", "me.png", "application/octet-stream", png()))
                        .with(jwt()
                                .jwt(token -> token.claim("uid", user.getId().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isAccepted());

        List<UUID> rows = jdbcTemplate.queryForList(
                "select id from outbox_messages where queue = ? and payload ->> 'userId' = ?",
                UUID.class,
                AvatarQueues.PROCESS,
                user.getId().toString()
        );
        assertThat(rows).hasSize(1);
        OutboxMessage row = awaitPublished(rows.getFirst());
        UUID uploadId = UUID.fromString((String) row.getPayload().get("uploadId"));

        assertThat(row.getType()).isEqualTo(AvatarUploaded.class.getName());
        await().atMost(PUBLISH_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> pendingAvatarId(user) == null && avatarId(user) != null);
        assertThat(avatarId(user)).isNotEqualTo(uploadId);
        assertThat(mediaExists(uploadId)).isFalse();
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    // A separate thread has its own transaction, as a concurrent publisher would
    private <T> T inAnotherTransaction(Supplier<T> work) {
        return CompletableFuture.supplyAsync(() -> inTransaction().execute(status -> work.get())).join();
    }

    /** Holds {@code SELECT ... FOR UPDATE} on the rows until the returned connection rolls back or closes. */
    private Connection lockRows(UUID... ids) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);

        try (PreparedStatement statement = connection.prepareStatement(
                "select id from outbox_messages where id = any(?) for update")) {
            statement.setArray(1, connection.createArrayOf("uuid", ids));
            statement.executeQuery();
        }

        return connection;
    }

    private UUID insertRow(Instant createdAt, Instant publishedAt) {
        return insertRow(createdAt, publishedAt, Instant.now().plus(Duration.ofDays(1)));
    }

    // The queue matches no binding and the next attempt is a day away, so neither the poller nor the broker
    // ever acts on these rows
    private UUID insertRow(Instant createdAt, Instant publishedAt, Instant nextAttemptAt) {
        OutboxMessage row = new OutboxMessage();

        row.setQueue("outbox-tests." + UUID.randomUUID());
        row.setType(MailMessage.class.getName());
        row.setPayload(Map.of("to", uniqueEmail(), "subject", "Never published", "text", "Inserted by the test"));
        row.setCreatedAt(createdAt.truncatedTo(ChronoUnit.MICROS));
        row.setNextAttemptAt(nextAttemptAt.truncatedTo(ChronoUnit.MICROS));
        row.setPublishedAt(publishedAt);

        UUID id = repository.save(row).getId();
        insertedRows.add(id);
        return id;
    }

    private static Instant inTwoDays() {
        return Instant.now().plus(Duration.ofDays(2));
    }

    private static List<UUID> ids(Collection<OutboxMessage> rows) {
        return rows.stream().map(OutboxMessage::getId).toList();
    }

    private List<UUID> rowIdsSentTo(String email) {
        return jdbcTemplate.queryForList(
                "select id from outbox_messages where payload ->> 'to' = ?",
                UUID.class,
                email
        );
    }

    private UUID rowIdSentTo(String email) {
        List<UUID> ids = rowIdsSentTo(email);

        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    // The relay publishes on an async thread after commit, and sets published_at once the broker confirmed
    private OutboxMessage awaitPublished(UUID id) {
        await().atMost(PUBLISH_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> repository.findById(id).orElseThrow().getPublishedAt() != null);

        return repository.findById(id).orElseThrow();
    }

    private User createUser() {
        User user = new User();

        user.setEmail(uniqueEmail());
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setEmailVerifiedAt(Instant.now());
        user.setDisplayName("Outbox " + UUID.randomUUID().toString().substring(0, 8));
        user.setRoles(EnumSet.of(UserRole.USER));

        return userRepository.saveAndFlush(user);
    }

    private UUID avatarId(User user) {
        return jdbcTemplate.queryForObject("select avatar_media_id from users where id = ?", UUID.class, user.getId());
    }

    private UUID pendingAvatarId(User user) {
        return jdbcTemplate.queryForObject(
                "select pending_avatar_media_id from users where id = ?", UUID.class, user.getId());
    }

    private boolean mediaExists(UUID id) {
        return jdbcTemplate.queryForObject("select count(*) from media where id = ?", Integer.class, id) > 0;
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.GREEN);
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static String uniqueEmail() {
        return "outbox-" + UUID.randomUUID() + "@example.com";
    }
}
