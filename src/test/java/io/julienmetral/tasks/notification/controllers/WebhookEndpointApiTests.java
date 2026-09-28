package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.notification.webhook.WebhookSecrets;
import io.julienmetral.tasks.support.IntegrationTest;
import io.julienmetral.tasks.support.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.julienmetral.tasks.support.Problems.invalidBodyValue;
import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class WebhookEndpointApiTests {

    private static final String WEBHOOKS = "/api/v1/users/{id}/webhooks";

    private static final String WEBHOOK = WEBHOOKS + "/{webhookId}";

    private static final String SECRET = WEBHOOK + "/secret";

    // Allowed by the test configuration: HTTPS is not required and 127.0.0.1/32 is an allowed address
    private static final String LOCAL_URL = "http://127.0.0.1:8080/hooks";

    private static final String PUBLIC_URL = "https://93.184.216.34/tasks";

    private static final String SECRET_FORMAT = "whsec_[A-Za-z0-9+/]{43}=";

    private static final String SLACK_MASK = "https://hooks.slack.com/services/****";

    private static final String NOT_SLACK = "A Slack webhook URL must start with https://hooks.slack.com/services/";

    private static final Instant NOW = Instant.parse("2031-02-03T04:05:06.123456Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRetentionQueries retentionQueries;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WebhookSecrets webhookSecrets;

    @Autowired
    private TestClock clock;

    // Creation

    @Test
    void locationHeaderNamesTheCreatedWebhook() throws Exception {
        User user = createUser(UserRole.USER);

        ResultActions result = create(user, asUser(user), LOCAL_URL, "task.assigned").andExpect(status().isCreated());

        String id = json(result).get("id").asString();
        result.andExpect(header().string("Location", "/api/v1/users/" + user.getId() + "/webhooks/" + id));
    }

    @Test
    void createReturnsTheWebhookWithItsSecretAndItsEventsInCanonicalOrder() throws Exception {
        User user = createUser(UserRole.USER);
        clock.set(NOW);

        create(user, asUser(user), LOCAL_URL, "task.overdue", "task.assigned", "task.due_soon")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.url").value(LOCAL_URL))
                .andExpect(jsonPath("$.events").value(contains("task.assigned", "task.due_soon", "task.overdue")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.secret").value(matchesPattern(SECRET_FORMAT)));
    }

    @Test
    void createStoresTheUrlTheTimestampsAndEachEventOnce() throws Exception {
        User user = createUser(UserRole.USER);
        clock.set(NOW);

        UUID webhookId =
                createdId(user, asUser(user), PUBLIC_URL, "task.commented", "task.mentioned", "task.commented");

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("user_id")).isEqualTo(user.getId());
        assertThat(row.get("url")).isEqualTo(PUBLIC_URL);
        assertThat(instant(row.get("created_at"))).isEqualTo(NOW);
        assertThat(instant(row.get("updated_at"))).isEqualTo(NOW);
        assertThat(row.get("disabled_at")).isNull();
        assertThat(row.get("disabled_reason")).isNull();
        assertThat(row.get("previous_secret")).isNull();
        assertThat(row.get("previous_secret_expires_at")).isNull();
        assertThat(storedEvents(webhookId)).containsExactly("TASK_COMMENTED", "TASK_MENTIONED");
    }

    @Test
    void createStoresTheSecretEncryptedAndNeverInClear() throws Exception {
        User user = createUser(UserRole.USER);

        JsonNode created = json(create(user, asUser(user), LOCAL_URL, "task.assigned").andExpect(status().isCreated()));
        String secret = created.get("secret").asString();

        String stored = storedSecret(UUID.fromString(created.get("id").asString()));
        assertThat(stored)
                .startsWith("v1:")
                .doesNotContain(secret)
                .doesNotContain(secret.substring("whsec_".length()));
        assertThat(webhookSecrets.decrypt(stored)).isEqualTo(secret);
    }

    @Test
    void everyWebhookGetsItsOwnSecret() throws Exception {
        User user = createUser(UserRole.USER);

        String first = json(create(user, asUser(user), LOCAL_URL, "task.assigned")).get("secret").asString();
        String second = json(create(user, asUser(user), LOCAL_URL, "task.assigned")).get("secret").asString();

        assertThat(first).isNotEqualTo(second);
    }

    // Reading

    @Test
    void listReturnsTheWebhooksOldestFirstWithoutTheirSecrets() throws Exception {
        User user = createUser(UserRole.USER);
        clock.set(NOW.plus(Duration.ofMinutes(2)));
        UUID newest = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW);
        UUID oldest = createdId(user, asUser(user), PUBLIC_URL, "task.overdue");
        clock.set(NOW.plus(Duration.ofMinutes(1)));
        UUID middle = createdId(user, asUser(user), LOCAL_URL, "task.deleted");

        mockMvc.perform(get(WEBHOOKS, user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(oldest.toString(), middle.toString(), newest.toString())))
                .andExpect(jsonPath("$[0].url").value(PUBLIC_URL))
                .andExpect(jsonPath("$[0].events").value(contains("task.overdue")))
                .andExpect(jsonPath("$[*].secret").value(empty()))
                .andExpect(jsonPath("$[*].previousSecret").value(empty()));
    }

    @Test
    void listOfAUserWithoutWebhooksIsEmpty() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get(WEBHOOKS, user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").value(empty()));
    }

    @Test
    void getReturnsTheWebhookWithoutItsSecret() throws Exception {
        User user = createUser(UserRole.USER);
        clock.set(NOW);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.unassigned", "task.cancelled");

        mockMvc.perform(get(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.url").value(LOCAL_URL))
                .andExpect(jsonPath("$.events").value(contains("task.unassigned", "task.cancelled")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()))
                .andExpect(jsonPath("$.disabledAt").value(nullValue()))
                .andExpect(jsonPath("$.previousSecretExpiresAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.previousSecret").doesNotExist());
    }

    // Update

    @Test
    void updateReplacesTheUrlAndTheEvents() throws Exception {
        User user = createUser(UserRole.USER);
        clock.set(NOW);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned", "task.unassigned");
        clock.set(NOW.plus(Duration.ofHours(1)));

        update(user, webhookId, asUser(user), PUBLIC_URL, true, "task.due_soon")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.events").value(contains("task.due_soon")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.updatedAt").value(NOW.plus(Duration.ofHours(1)).toString()))
                .andExpect(jsonPath("$.secret").doesNotExist());

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("url")).isEqualTo(PUBLIC_URL);
        assertThat(instant(row.get("updated_at"))).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(storedEvents(webhookId)).containsExactly("TASK_DUE_SOON");
    }

    @Test
    void updateKeepsTheSecret() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        String before = storedSecret(webhookId);

        update(user, webhookId, asUser(user), PUBLIC_URL, false, "task.overdue").andExpect(status().isOk());

        assertThat(storedSecret(webhookId)).isEqualTo(before);
    }

    @Test
    void disablingRecordsTheOwnerAsTheReason() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW);

        update(user, webhookId, asUser(user), LOCAL_URL, false, "task.assigned")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("OWNER"))
                .andExpect(jsonPath("$.disabledAt").value(NOW.toString()));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("disabled_reason")).isEqualTo("OWNER");
        assertThat(instant(row.get("disabled_at"))).isEqualTo(NOW);
    }

    @Test
    void disablingAgainKeepsTheFirstDisablingTime() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW);
        update(user, webhookId, asUser(user), LOCAL_URL, false, "task.assigned").andExpect(status().isOk());
        clock.set(NOW.plus(Duration.ofDays(1)));

        update(user, webhookId, asUser(user), LOCAL_URL, false, "task.overdue")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledAt").value(NOW.toString()))
                .andExpect(jsonPath("$.updatedAt").value(NOW.plus(Duration.ofDays(1)).toString()));
    }

    @Test
    void enablingClearsTheDisabling() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        update(user, webhookId, asUser(user), LOCAL_URL, false, "task.assigned").andExpect(status().isOk());

        update(user, webhookId, asUser(user), LOCAL_URL, true, "task.assigned")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").value(nullValue()))
                .andExpect(jsonPath("$.disabledAt").value(nullValue()));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("disabled_reason")).isNull();
        assertThat(row.get("disabled_at")).isNull();
    }

    @Test
    void updateToARefusedUrlReturnsTheProblemAndChangesNothing() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");

        update(user, webhookId, asUser(user), "http://10.0.0.1/hooks", false, "task.overdue")
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value("The URL must point to a public address"));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("url")).isEqualTo(LOCAL_URL);
        assertThat(row.get("disabled_at")).isNull();
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
    }

    @Test
    void updateThatKeepsTheUrlDoesNotCheckItAgain() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        // A URL accepted when it was declared, and refused by the current policy
        jdbcTemplate.update("UPDATE webhook_endpoints SET url = 'http://10.0.0.1/hooks' WHERE id = ?", webhookId);

        update(user, webhookId, asUser(user), "http://10.0.0.1/hooks", true, "task.overdue")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").value(contains("task.overdue")));
    }

    // Deletion

    @Test
    void deleteRemovesTheWebhookAndItsEvents() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned", "task.overdue");
        UUID kept = createdId(user, asUser(user), LOCAL_URL, "task.assigned");

        mockMvc.perform(delete(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(status().isNoContent());

        assertThat(endpointRows(webhookId)).isZero();
        assertThat(storedEvents(webhookId)).isEmpty();
        assertThat(endpointRows(kept)).isOne();
        mockMvc.perform(get(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + webhookId));
    }

    @Test
    void deletingTwiceReturnsNotFound() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        mockMvc.perform(delete(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(untypedProblem(404, "Not Found"));
    }

    // Secret rotation

    @Test
    void rotationReturnsANewSecretAndWhenThePreviousOneExpires() throws Exception {
        User user = createUser(UserRole.USER);
        JsonNode created = json(create(user, asUser(user), LOCAL_URL, "task.assigned"));
        UUID webhookId = UUID.fromString(created.get("id").asString());
        clock.set(NOW);

        JsonNode rotated = json(mockMvc.perform(post(SECRET, user.getId(), webhookId).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousSecretExpiresAt").value(NOW.plus(Duration.ofHours(24)).toString())));

        assertThat(rotated.get("secret").asString())
                .matches(SECRET_FORMAT)
                .isNotEqualTo(created.get("secret").asString());
        assertThat(rotated.propertyNames()).containsExactlyInAnyOrder("secret", "previousSecretExpiresAt");
    }

    @Test
    void rotationKeepsThePreviousEncryptedSecretUntilItExpires() throws Exception {
        User user = createUser(UserRole.USER);
        JsonNode created = json(create(user, asUser(user), LOCAL_URL, "task.assigned"));
        UUID webhookId = UUID.fromString(created.get("id").asString());
        String encryptedBefore = storedSecret(webhookId);
        clock.set(NOW);

        String newSecret = rotate(user, webhookId, asUser(user));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("previous_secret")).isEqualTo(encryptedBefore);
        assertThat(webhookSecrets.decrypt((String) row.get("previous_secret")))
                .isEqualTo(created.get("secret").asString());
        assertThat(instant(row.get("previous_secret_expires_at"))).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(instant(row.get("updated_at"))).isEqualTo(NOW);
        assertThat((String) row.get("secret")).startsWith("v1:").doesNotContain(newSecret);
        assertThat(webhookSecrets.decrypt((String) row.get("secret"))).isEqualTo(newSecret);
    }

    @Test
    void readingAfterARotationShowsTheExpiryButNoSecret() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW);
        rotate(user, webhookId, asUser(user));

        mockMvc.perform(get(WEBHOOK, user.getId(), webhookId).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousSecretExpiresAt").value(NOW.plus(Duration.ofHours(24)).toString()))
                .andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.previousSecret").doesNotExist());
    }

    @Test
    void secondRotationReplacesThePreviousSecretWithTheFirstRotatedOne() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW);
        String firstRotated = rotate(user, webhookId, asUser(user));
        clock.set(NOW.plus(Duration.ofHours(2)));

        String secondRotated = rotate(user, webhookId, asUser(user));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(webhookSecrets.decrypt((String) row.get("previous_secret"))).isEqualTo(firstRotated);
        assertThat(webhookSecrets.decrypt((String) row.get("secret"))).isEqualTo(secondRotated);
        assertThat(instant(row.get("previous_secret_expires_at")))
                .isEqualTo(NOW.plus(Duration.ofHours(2)).plus(Duration.ofHours(24)));
    }

    // Limit

    @Test
    void sixthWebhookIsRefusedAndNotStored() throws Exception {
        User user = createUser(UserRole.USER);
        for (int i = 0; i < 5; i++) {
            create(user, asUser(user), LOCAL_URL, "task.assigned").andExpect(status().isCreated());
        }

        create(user, asUser(user), LOCAL_URL, "task.assigned")
                .andExpect(typedProblem(422, "webhook-limit-reached", "Webhook limit reached"))
                .andExpect(jsonPath("$.detail").value("A user can declare at most 5 webhooks"));

        assertThat(webhookCount(user.getId())).isEqualTo(5);
    }

    @Test
    void deletingAWebhookAtTheLimitMakesRoomForAnother() throws Exception {
        User user = createUser(UserRole.USER);
        UUID first = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        for (int i = 0; i < 4; i++) {
            create(user, asUser(user), LOCAL_URL, "task.assigned").andExpect(status().isCreated());
        }
        mockMvc.perform(delete(WEBHOOK, user.getId(), first).with(asUser(user))).andExpect(status().isNoContent());

        create(user, asUser(user), LOCAL_URL, "task.assigned").andExpect(status().isCreated());

        assertThat(webhookCount(user.getId())).isEqualTo(5);
    }

    @Test
    void limitCountsOnlyTheUsersOwnWebhooks() throws Exception {
        User full = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        for (int i = 0; i < 5; i++) {
            create(full, asUser(full), LOCAL_URL, "task.assigned").andExpect(status().isCreated());
        }

        create(other, asUser(other), LOCAL_URL, "task.assigned").andExpect(status().isCreated());
    }

    // Refused URLs

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://10.0.0.1/                          | The URL must point to a public address",
            "http://169.254.169.254/latest/meta-data/  | The URL must point to a public address",
            "http://192.168.1.10:8080/hooks            | The URL must point to a public address",
            "http://127.0.0.2:8080/hooks               | The URL must point to a public address",
            "http://[::1]:8080/hooks                   | The URL must point to a public address",
            "http://user:secret@127.0.0.1:8080/hooks   | The URL must not contain credentials",
            "ftp://127.0.0.1/hooks                     | The URL must be an absolute http or https URL",
            "/hooks                                    | The URL must be an absolute http or https URL",
            "http://webhook-receiver.invalid/hooks     | The host of the URL cannot be resolved",
            "http://127.0.0.1:8080/hooks with a space  | The URL is not valid"
    })
    void refusedUrlReturnsTheProblemWithItsReasonAndStoresNothing(String url, String reason) throws Exception {
        User user = createUser(UserRole.USER);

        create(user, asUser(user), url, "task.assigned")
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value(reason));

        assertThat(webhookCount(user.getId())).isZero();
    }

    // Slack webhooks

    @Test
    void createSlackWebhookReturnsItsKindAndTheMaskedUrlWithoutASecret() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();
        clock.set(NOW);

        ResultActions result = createSlack(user, asUser(user), url, "task.overdue", "task.assigned")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(jsonPath("$.events").value(contains("task.assigned", "task.overdue")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(content().string(not(containsString(pathToken(url)))));

        String id = json(result).get("id").asString();
        result.andExpect(header().string("Location", "/api/v1/users/" + user.getId() + "/webhooks/" + id));
    }

    @Test
    void createSlackWebhookStoresItsUrlEncrypted() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();

        UUID webhookId = createdSlackId(user, url, "task.assigned");

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("kind")).isEqualTo("SLACK");
        assertThat((String) row.get("url"))
                .startsWith("v1:")
                .doesNotContain("hooks.slack.com")
                .doesNotContain(pathToken(url));
        assertThat(webhookSecrets.decrypt((String) row.get("url"))).isEqualTo(url);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
    }

    @Test
    void twoSlackWebhooksWithTheSameUrlAreEncryptedDifferently() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();

        UUID first = createdSlackId(user, url, "task.assigned");
        UUID second = createdSlackId(user, url, "task.assigned");

        assertThat(storedUrl(first)).isNotEqualTo(storedUrl(second));
        assertThat(webhookSecrets.decrypt(storedUrl(second))).isEqualTo(url);
    }

    @ParameterizedTest
    @CsvSource({
            "http://hooks.slack.com/services/T0001/B0002/token-http",
            "https://hooks.slack.com.example.com/services/T0001/B0002/token-host",
            "https://evil.hooks.slack.com/services/T0001/B0002/token-subdomain",
            "https://hooks.slack.com/workflows/T0001/B0002/token-path",
            "https://hooks.slack.com/services/T0001/B0002/token-query?channel=general",
            "https://user:secret@hooks.slack.com/services/T0001/B0002/token-credentials",
            "https://hooks.slack.com:8443/services/T0001/B0002/token-port",
            "http://127.0.0.1:8080/hooks",
            "https://93.184.216.34/tasks"
    })
    void slackWebhookWithAUrlThatIsNotSlacksIsRefusedAndNotStored(String url) throws Exception {
        User user = createUser(UserRole.USER);

        createSlack(user, asUser(user), url, "task.assigned")
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value(NOT_SLACK));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void webhookCreatedWithoutAKindOrWithTheWebhookKindIsAStandardWebhookWithItsUrlInClear() throws Exception {
        User user = createUser(UserRole.USER);

        JsonNode withoutKind = json(create(user, asUser(user), LOCAL_URL, "task.assigned")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WEBHOOK"))
                .andExpect(jsonPath("$.url").value(LOCAL_URL))
                .andExpect(jsonPath("$.secret").value(matchesPattern(SECRET_FORMAT))));
        JsonNode withKind = json(postBody(user, asUser(user),
                "{\"kind\": \"WEBHOOK\", \"url\": \"" + LOCAL_URL + "\", \"events\": [\"task.assigned\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WEBHOOK"))
                .andExpect(jsonPath("$.secret").value(matchesPattern(SECRET_FORMAT))));

        for (JsonNode created : List.of(withoutKind, withKind)) {
            Map<String, Object> row = endpointRow(UUID.fromString(created.get("id").asString()));
            assertThat(row.get("kind")).isEqualTo("WEBHOOK");
            assertThat(row.get("url")).isEqualTo(LOCAL_URL);
        }
    }

    @Test
    void listAndGetShowTheKindOfEachWebhookAndMaskTheSlackUrl() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();
        clock.set(NOW);
        UUID webhook = createdId(user, asUser(user), LOCAL_URL, "task.assigned");
        clock.set(NOW.plus(Duration.ofMinutes(1)));
        UUID slack = createdSlackId(user, url, "task.overdue");

        mockMvc.perform(get(WEBHOOKS, user.getId()).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(webhook.toString(), slack.toString())))
                .andExpect(jsonPath("$[*].kind").value(contains("WEBHOOK", "SLACK")))
                .andExpect(jsonPath("$[*].url").value(contains(LOCAL_URL, SLACK_MASK)))
                .andExpect(content().string(not(containsString(pathToken(url)))));
        mockMvc.perform(get(WEBHOOK, user.getId(), slack).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(content().string(not(containsString(pathToken(url)))));
    }

    @Test
    void updateWithTheMaskedUrlKeepsTheSlackUrl() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();
        UUID webhookId = createdSlackId(user, url, "task.assigned");
        String storedBefore = storedUrl(webhookId);
        clock.set(NOW);

        update(user, webhookId, asUser(user), SLACK_MASK, true, "task.due_soon")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(jsonPath("$.events").value(contains("task.due_soon")))
                .andExpect(jsonPath("$.updatedAt").value(NOW.toString()));

        assertThat(storedUrl(webhookId)).isEqualTo(storedBefore);
        assertThat(webhookSecrets.decrypt(storedUrl(webhookId))).isEqualTo(url);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_DUE_SOON");
    }

    @Test
    void updateWithTheSameSlackUrlKeepsTheStoredValue() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();
        UUID webhookId = createdSlackId(user, url, "task.assigned");
        String storedBefore = storedUrl(webhookId);

        update(user, webhookId, asUser(user), url, false, "task.assigned")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(jsonPath("$.disabledReason").value("OWNER"));

        assertThat(storedUrl(webhookId)).isEqualTo(storedBefore);
    }

    @Test
    void updateWithAnotherSlackUrlReplacesItEncrypted() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdSlackId(user, slackUrl(), "task.assigned");
        String replacement = slackUrl();

        update(user, webhookId, asUser(user), replacement, true, "task.assigned")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(content().string(not(containsString(pathToken(replacement)))));

        assertThat(storedUrl(webhookId)).startsWith("v1:").doesNotContain(pathToken(replacement));
        assertThat(webhookSecrets.decrypt(storedUrl(webhookId))).isEqualTo(replacement);
    }

    @ParameterizedTest
    @CsvSource({
            "http://127.0.0.1:8080/hooks",
            "http://hooks.slack.com/services/T0001/B0002/token-http",
            "https://hooks.slack.com/****"
    })
    void updateOfASlackWebhookToAUrlThatIsNotSlacksIsRefusedAndChangesNothing(String url) throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdSlackId(user, slackUrl(), "task.assigned");
        Map<String, Object> before = endpointRow(webhookId);

        update(user, webhookId, asUser(user), url, false, "task.overdue")
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value(NOT_SLACK));

        assertThat(endpointRow(webhookId)).isEqualTo(before);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
    }

    @Test
    void kindSentOnUpdateDoesNotTurnASlackWebhookIntoAStandardOne() throws Exception {
        User user = createUser(UserRole.USER);
        String url = slackUrl();
        UUID webhookId = createdSlackId(user, url, "task.assigned");

        putBody(user, webhookId, "{\"kind\": \"WEBHOOK\", \"url\": \"" + LOCAL_URL
                + "\", \"events\": [\"task.assigned\"], \"enabled\": true}")
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value(NOT_SLACK));
        putBody(user, webhookId, "{\"kind\": \"WEBHOOK\", \"url\": \"" + SLACK_MASK
                + "\", \"events\": [\"task.assigned\"], \"enabled\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"));

        assertThat(endpointRow(webhookId).get("kind")).isEqualTo("SLACK");
        assertThat(webhookSecrets.decrypt(storedUrl(webhookId))).isEqualTo(url);
    }

    @Test
    void kindSentOnUpdateDoesNotTurnAStandardWebhookIntoASlackOne() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");

        putBody(user, webhookId, "{\"kind\": \"SLACK\", \"url\": \"" + LOCAL_URL
                + "\", \"events\": [\"task.assigned\"], \"enabled\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("WEBHOOK"))
                .andExpect(jsonPath("$.url").value(LOCAL_URL));

        Map<String, Object> row = endpointRow(webhookId);
        assertThat(row.get("kind")).isEqualTo("WEBHOOK");
        assertThat(row.get("url")).isEqualTo(LOCAL_URL);
    }

    // Access

    @Test
    void adminManagesTheWebhooksOfAnotherUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);

        ResultActions created = create(owner, asAdmin(admin), LOCAL_URL, "task.assigned")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty());
        UUID webhookId = UUID.fromString(json(created).get("id").asString());
        created.andExpect(header().string("Location", "/api/v1/users/" + owner.getId() + "/webhooks/" + webhookId));
        assertThat(endpointRow(webhookId).get("user_id")).isEqualTo(owner.getId());

        mockMvc.perform(get(WEBHOOKS, owner.getId()).with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(webhookId.toString())));
        mockMvc.perform(get(WEBHOOK, owner.getId(), webhookId).with(asAdmin(admin)))
                .andExpect(status().isOk());
        update(owner, webhookId, asAdmin(admin), PUBLIC_URL, false, "task.overdue")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledReason").value("OWNER"));
        rotate(owner, webhookId, asAdmin(admin));
        mockMvc.perform(delete(WEBHOOK, owner.getId(), webhookId).with(asAdmin(admin)))
                .andExpect(status().isNoContent());

        assertThat(endpointRows(webhookId)).isZero();
        assertThat(webhookCount(admin.getId())).isZero();
    }

    @Test
    void userCannotTouchTheWebhooksOfAnotherUser() throws Exception {
        User owner = createUser(UserRole.USER);
        User intruder = createUser(UserRole.USER);
        UUID webhookId = createdId(owner, asUser(owner), LOCAL_URL, "task.assigned");
        Map<String, Object> before = endpointRow(webhookId);

        create(owner, asUser(intruder), LOCAL_URL, "task.assigned").andExpect(status().isForbidden());
        mockMvc.perform(get(WEBHOOKS, owner.getId()).with(asUser(intruder))).andExpect(status().isForbidden());
        mockMvc.perform(get(WEBHOOK, owner.getId(), webhookId).with(asUser(intruder)))
                .andExpect(status().isForbidden());
        update(owner, webhookId, asUser(intruder), PUBLIC_URL, false, "task.overdue")
                .andExpect(status().isForbidden());
        mockMvc.perform(post(SECRET, owner.getId(), webhookId).with(asUser(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(WEBHOOK, owner.getId(), webhookId).with(asUser(intruder)))
                .andExpect(status().isForbidden());

        assertThat(endpointRow(webhookId)).isEqualTo(before);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
        assertThat(webhookCount(owner.getId())).isOne();
    }

    @Test
    void webhookOfAnotherUserUnderTheCallersOwnPathIsNotFound() throws Exception {
        User owner = createUser(UserRole.USER);
        User intruder = createUser(UserRole.USER);
        UUID webhookId = createdId(owner, asUser(owner), LOCAL_URL, "task.assigned");
        Map<String, Object> before = endpointRow(webhookId);
        String notFound = "Webhook not found with id: " + webhookId;

        mockMvc.perform(get(WEBHOOK, intruder.getId(), webhookId).with(asUser(intruder)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value(notFound));
        update(intruder, webhookId, asUser(intruder), PUBLIC_URL, false, "task.overdue")
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value(notFound));
        mockMvc.perform(post(SECRET, intruder.getId(), webhookId).with(asUser(intruder)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value(notFound));
        mockMvc.perform(delete(WEBHOOK, intruder.getId(), webhookId).with(asUser(intruder)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value(notFound));

        assertThat(endpointRow(webhookId)).isEqualTo(before);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
    }

    @Test
    void adminAddressingAWebhookUnderTheWrongUserGetsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User owner = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        UUID webhookId = createdId(owner, asUser(owner), LOCAL_URL, "task.assigned");

        mockMvc.perform(get(WEBHOOK, other.getId(), webhookId).with(asAdmin(admin)))
                .andExpect(untypedProblem(404, "Not Found"));
        mockMvc.perform(delete(WEBHOOK, other.getId(), webhookId).with(asAdmin(admin)))
                .andExpect(untypedProblem(404, "Not Found"));

        assertThat(endpointRows(webhookId)).isOne();
    }

    @Test
    void unknownWebhookIdIsNotFound() throws Exception {
        User user = createUser(UserRole.USER);
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get(WEBHOOK, user.getId(), unknown).with(asUser(user)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + unknown));
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get(WEBHOOKS, unknown).with(asAdmin(admin)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found with id: " + unknown));
        mockMvc.perform(post(WEBHOOKS, unknown)
                        .with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOCAL_URL, "task.assigned")))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found with id: " + unknown));

        assertThat(webhookCount(unknown)).isZero();
    }

    @Test
    void softDeletedUserIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createUser(UserRole.USER);
        userService.delete(deleted.getId());

        mockMvc.perform(get(WEBHOOKS, deleted.getId()).with(asAdmin(admin)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found with id: " + deleted.getId()));
        create(deleted, asAdmin(admin), LOCAL_URL, "task.assigned")
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found with id: " + deleted.getId()));

        assertThat(webhookCount(deleted.getId())).isZero();
    }

    @Test
    void webhookOfASoftDeletedUserIsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User deleted = createUser(UserRole.USER);
        UUID webhookId = createdId(deleted, asUser(deleted), LOCAL_URL, "task.assigned");
        userService.delete(deleted.getId());
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(WEBHOOK, deleted.getId(), webhookId),
                put(WEBHOOK, deleted.getId(), webhookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"" + LOCAL_URL + "\", \"events\": [\"task.overdue\"], \"enabled\": true}"),
                post(SECRET, deleted.getId(), webhookId),
                delete(WEBHOOK, deleted.getId(), webhookId)
        );
        List<Integer> statuses = new ArrayList<>();

        for (MockHttpServletRequestBuilder request : requests) {
            statuses.add(mockMvc.perform(request.with(asAdmin(admin))).andReturn().getResponse().getStatus());
        }

        assertThat(statuses).containsExactly(404, 404, 404, 404);
    }

    // Validation

    @Test
    void createWithoutEventsIsRejected() throws Exception {
        User user = createUser(UserRole.USER);

        postBody(user, asUser(user), "{\"url\": \"" + LOCAL_URL + "\"}")
                .andExpect(invalidBodyValue("#/events", "must not be empty"));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void createWithAnEmptyEventListIsRejected() throws Exception {
        User user = createUser(UserRole.USER);

        postBody(user, asUser(user), "{\"url\": \"" + LOCAL_URL + "\", \"events\": []}")
                .andExpect(invalidBodyValue("#/events", "must not be empty"));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void createWithAnUnknownEventNameIsRejected() throws Exception {
        User user = createUser(UserRole.USER);

        postBody(user, asUser(user), "{\"url\": \"" + LOCAL_URL + "\", \"events\": [\"task.exploded\"]}")
                .andExpect(invalidBodyValue(
                        "#/events/0",
                        "must be one of task.assigned, task.unassigned, task.cancelled, task.deleted, "
                                + "task.commented, task.mentioned, task.due_soon, task.overdue"
                ));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void createWithTheConstantNameOfAnEventIsRejected() throws Exception {
        User user = createUser(UserRole.USER);

        postBody(user, asUser(user), "{\"url\": \"" + LOCAL_URL + "\", \"events\": [\"TASK_ASSIGNED\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/events/0"));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void createWithABlankUrlIsRejected() throws Exception {
        User user = createUser(UserRole.USER);

        postBody(user, asUser(user), "{\"url\": \"  \", \"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/url", "must not be blank"));

        assertThat(webhookCount(user.getId())).isZero();
    }

    @Test
    void updateWithoutEventsIsRejectedAndChangesNothing() throws Exception {
        User user = createUser(UserRole.USER);
        UUID webhookId = createdId(user, asUser(user), LOCAL_URL, "task.assigned");

        mockMvc.perform(put(WEBHOOK, user.getId(), webhookId)
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"" + PUBLIC_URL + "\", \"events\": [], \"enabled\": false}"))
                .andExpect(invalidBodyValue("#/events", "must not be empty"));

        assertThat(endpointRow(webhookId).get("url")).isEqualTo(LOCAL_URL);
        assertThat(storedEvents(webhookId)).containsExactly("TASK_ASSIGNED");
    }

    // Erasure

    @Test
    void erasingAUserDeletesTheirWebhooksAndTheirEvents() throws Exception {
        User erased = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);
        UUID first = createdId(erased, asUser(erased), LOCAL_URL, "task.assigned", "task.overdue");
        UUID second = createdId(erased, asUser(erased), PUBLIC_URL, "task.commented");
        UUID kept = createdId(other, asUser(other), LOCAL_URL, "task.assigned");
        userService.delete(erased.getId());
        // Dated long before anything the other test classes delete, so the erasure below reaches only this user
        Instant deletedAt = Instant.parse("1990-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), erased.getId());

        List<UUID> erasedIds = retentionQueries.anonymizeUsersDeletedBefore(deletedAt.plusSeconds(1), clock.instant());

        assertThat(erasedIds).contains(erased.getId());
        assertThat(webhookCount(erased.getId())).isZero();
        assertThat(storedEvents(first)).isEmpty();
        assertThat(storedEvents(second)).isEmpty();
        assertThat(endpointRows(kept)).isOne();
        assertThat(storedEvents(kept)).containsExactly("TASK_ASSIGNED");
    }

    private ResultActions create(User owner, RequestPostProcessor caller, String url, String... events)
            throws Exception {
        return postBody(owner, caller, body(url, events));
    }

    private ResultActions postBody(User owner, RequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(post(WEBHOOKS, owner.getId())
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID createdId(User owner, RequestPostProcessor caller, String url, String... events) throws Exception {
        return UUID.fromString(json(create(owner, caller, url, events).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private ResultActions createSlack(User owner, RequestPostProcessor caller, String url, String... events)
            throws Exception {
        return postBody(owner, caller, """
                {"kind": "SLACK", "url": "%s", "events": %s}
                """.formatted(url, eventArray(events)));
    }

    private UUID createdSlackId(User owner, String url, String... events) throws Exception {
        return UUID.fromString(json(createSlack(owner, asUser(owner), url, events).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private ResultActions putBody(User owner, UUID webhookId, String body) throws Exception {
        return mockMvc.perform(put(WEBHOOK, owner.getId(), webhookId)
                .with(asUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String slackUrl() {
        return "https://hooks.slack.com/services/T0001/B0002/token-" + UUID.randomUUID();
    }

    private static String pathToken(String slackUrl) {
        return slackUrl.substring(slackUrl.lastIndexOf('/') + 1);
    }

    private ResultActions update(
            User owner,
            UUID webhookId,
            RequestPostProcessor caller,
            String url,
            boolean enabled,
            String... events
    ) throws Exception {
        return mockMvc.perform(put(WEBHOOK, owner.getId(), webhookId)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"url": "%s", "events": %s, "enabled": %s}
                        """.formatted(url, eventArray(events), enabled)));
    }

    private String rotate(User owner, UUID webhookId, RequestPostProcessor caller) throws Exception {
        return json(mockMvc.perform(post(SECRET, owner.getId(), webhookId).with(caller))
                .andExpect(status().isOk()))
                .get("secret").asString();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String body(String url, String... events) {
        return """
                {"url": "%s", "events": %s}
                """.formatted(url, eventArray(events));
    }

    private static String eventArray(String... events) {
        return Arrays.stream(events)
                .map(event -> "\"" + event + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private Map<String, Object> endpointRow(UUID webhookId) {
        return jdbcTemplate.queryForMap("SELECT * FROM webhook_endpoints WHERE id = ?", webhookId);
    }

    private int endpointRows(UUID webhookId) {
        return count("SELECT count(*) FROM webhook_endpoints WHERE id = ?", webhookId);
    }

    private int webhookCount(UUID userId) {
        return count("SELECT count(*) FROM webhook_endpoints WHERE user_id = ?", userId);
    }

    private String storedUrl(UUID webhookId) {
        return jdbcTemplate.queryForObject("SELECT url FROM webhook_endpoints WHERE id = ?", String.class, webhookId);
    }

    private String storedSecret(UUID webhookId) {
        return jdbcTemplate.queryForObject(
                "SELECT secret FROM webhook_endpoints WHERE id = ?",
                String.class,
                webhookId
        );
    }

    private List<String> storedEvents(UUID webhookId) {
        return jdbcTemplate.queryForList(
                "SELECT event FROM webhook_endpoint_events WHERE endpoint_id = ? ORDER BY event",
                String.class,
                webhookId
        );
    }

    private int count(String sql, UUID id) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return count == null ? 0 : count;
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail("webhooks-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setDisplayName("Webhook " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private static RequestPostProcessor asUser(User user) {
        return as(user, UserRole.USER);
    }

    private static RequestPostProcessor asAdmin(User user) {
        return as(user, UserRole.ADMIN);
    }

    private static RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }
}
