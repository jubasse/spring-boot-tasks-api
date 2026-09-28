package io.julienmetral.tasks.notification.controllers;

import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.notification.dtos.CreateWebhookEndpointDto;
import io.julienmetral.tasks.notification.dtos.UpdateWebhookEndpointDto;
import io.julienmetral.tasks.notification.entities.WebhookDelivery;
import io.julienmetral.tasks.notification.entities.WebhookDeliveryStatus;
import io.julienmetral.tasks.notification.entities.WebhookEndpoint;
import io.julienmetral.tasks.notification.entities.WebhookEvent;
import io.julienmetral.tasks.notification.entities.WebhookKind;
import io.julienmetral.tasks.notification.exceptions.WebhookDeliveryNotFoundException;
import io.julienmetral.tasks.notification.exceptions.WebhookEndpointNotFoundException;
import io.julienmetral.tasks.notification.exceptions.WebhookLimitReachedException;
import io.julienmetral.tasks.notification.exceptions.WebhookUrlNotAllowedException;
import io.julienmetral.tasks.notification.services.WebhookEndpointService;
import io.julienmetral.tasks.notification.services.WebhookEndpointService.CreatedWebhookEndpoint;
import io.julienmetral.tasks.notification.services.WebhookEndpointService.RotatedWebhookSecret;
import io.julienmetral.tasks.notification.webhook.WebhookTestResult;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.support.UserProfiles;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.invalidBodyValue;
import static io.julienmetral.tasks.support.Problems.invalidParameter;
import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.Problems.validationError;
import static io.julienmetral.tasks.support.Problems.withoutJavaTypeNames;
import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.user;
import static io.julienmetral.tasks.support.WebCallers.withoutUid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class WebhookEndpointControllerWebMvcTests {

    private static final String WEBHOOKS = "/api/v1/users/{id}/webhooks";

    private static final String WEBHOOK = WEBHOOKS + "/{webhookId}";

    private static final String SECRET = WEBHOOK + "/secret";

    private static final String DELIVERIES = WEBHOOK + "/deliveries";

    private static final String REDELIVER = DELIVERIES + "/{deliveryId}/redeliver";

    private static final String TEST = WEBHOOK + "/test";

    private static final String URL = "https://hooks.example.com/tasks";

    private static final String CREATE_BODY = """
            {"url": "https://hooks.example.com/tasks", "events": ["task.overdue", "task.assigned"]}
            """;

    private static final String UPDATE_BODY = """
            {"url": "https://hooks.example.com/tasks", "events": ["task.due_soon"], "enabled": false}
            """;

    private static final String SLACK_TOKEN = "slack-path-token";

    private static final String SLACK_URL = "https://hooks.slack.com/services/T0001/B0002/" + SLACK_TOKEN;

    private static final String SLACK_MASK = "https://hooks.slack.com/services/****";

    private static final String SLACK_CREATE_BODY = """
            {"kind": "SLACK", "url": "%s", "events": ["task.assigned"]}
            """.formatted(SLACK_URL);

    private static final Instant CREATED_AT = Instant.parse("2026-05-06T07:08:09Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookEndpointService webhookService;

    // Authentication and access

    @Test
    void everyEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();

        for (MockHttpServletRequestBuilder request : everyEndpoint(id, webhookId)) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(webhookService);
    }

    @Test
    void userCannotReachAnyEndpointOfAnotherUser() throws Exception {
        UUID caller = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        for (MockHttpServletRequestBuilder request : everyEndpoint(other, UUID.randomUUID())) {
            mockMvc.perform(request.with(user(caller))).andExpect(status().isForbidden());
        }

        verifyNoInteractions(webhookService);
    }

    @Test
    void callerWithoutUidCannotReachAnyEndpoint() throws Exception {
        for (MockHttpServletRequestBuilder request : everyEndpoint(UUID.randomUUID(), UUID.randomUUID())) {
            mockMvc.perform(request.with(withoutUid())).andExpect(status().isForbidden());
        }

        verifyNoInteractions(webhookService);
    }

    @Test
    void adminPassesTheCheckForAnotherUsersWebhooks() throws Exception {
        UUID other = UUID.randomUUID();
        when(webhookService.findAll(other)).thenReturn(List.of());

        mockMvc.perform(get(WEBHOOKS, other).with(admin(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(webhookService).findAll(other);
    }

    // Ids and bodies passed to the service, and the responses

    @Test
    void createPassesThePathIdAndTheParsedBody() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.create(eq(id), any()))
                .thenReturn(new CreatedWebhookEndpoint(endpoint(id, webhookId), "whsec_secret"));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id), CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + id + "/webhooks/" + webhookId))
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.events").value(contains("task.assigned", "task.overdue")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.secret").value("whsec_secret"));

        ArgumentCaptor<CreateWebhookEndpointDto> dto = ArgumentCaptor.forClass(CreateWebhookEndpointDto.class);
        verify(webhookService).create(eq(id), dto.capture());
        assertThat(dto.getValue().url()).isEqualTo(URL);
        assertThat(dto.getValue().events())
                .containsExactlyInAnyOrder(WebhookEvent.TASK_OVERDUE, WebhookEvent.TASK_ASSIGNED);
    }

    @Test
    void createPassesTheKindAndShowsASlackWebhookMaskedWithoutItsSecret() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.create(eq(id), any()))
                .thenReturn(new CreatedWebhookEndpoint(slackEndpoint(id, webhookId), "whsec_unused"));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id), SLACK_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + id + "/webhooks/" + webhookId))
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(content().string(not(containsString(SLACK_TOKEN))))
                .andExpect(content().string(not(containsString("whsec_unused"))));

        ArgumentCaptor<CreateWebhookEndpointDto> dto = ArgumentCaptor.forClass(CreateWebhookEndpointDto.class);
        verify(webhookService).create(eq(id), dto.capture());
        assertThat(dto.getValue().kind()).isEqualTo(WebhookKind.SLACK);
        assertThat(dto.getValue().url()).isEqualTo(SLACK_URL);
    }

    @Test
    void createPassesTheWebhookKindWhenItIsNamed() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.create(eq(id), any()))
                .thenReturn(new CreatedWebhookEndpoint(endpoint(id, webhookId), "whsec_secret"));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id),
                        "{\"kind\": \"WEBHOOK\", \"url\": \"" + URL + "\", \"events\": [\"task.assigned\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WEBHOOK"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.secret").value("whsec_secret"));

        ArgumentCaptor<CreateWebhookEndpointDto> dto = ArgumentCaptor.forClass(CreateWebhookEndpointDto.class);
        verify(webhookService).create(eq(id), dto.capture());
        assertThat(dto.getValue().kind()).isEqualTo(WebhookKind.WEBHOOK);
    }

    @Test
    void createWithoutAKindLeavesTheDefaultToTheService() throws Exception {
        UUID id = UUID.randomUUID();
        when(webhookService.create(eq(id), any()))
                .thenReturn(new CreatedWebhookEndpoint(endpoint(id, UUID.randomUUID()), "whsec_secret"));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id), CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WEBHOOK"));

        ArgumentCaptor<CreateWebhookEndpointDto> dto = ArgumentCaptor.forClass(CreateWebhookEndpointDto.class);
        verify(webhookService).create(eq(id), dto.capture());
        assertThat(dto.getValue().kind()).isNull();
    }

    @Test
    void listAndGetShowTheKindAndMaskTheUrlOfASlackWebhook() throws Exception {
        UUID id = UUID.randomUUID();
        UUID slack = UUID.randomUUID();
        UUID webhook = UUID.randomUUID();
        when(webhookService.findAll(id)).thenReturn(List.of(slackEndpoint(id, slack), endpoint(id, webhook)));
        when(webhookService.get(id, slack)).thenReturn(slackEndpoint(id, slack));

        mockMvc.perform(get(WEBHOOKS, id).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].kind").value(contains("SLACK", "WEBHOOK")))
                .andExpect(jsonPath("$[*].url").value(contains(SLACK_MASK, URL)))
                .andExpect(content().string(not(containsString(SLACK_TOKEN))));
        mockMvc.perform(get(WEBHOOK, id, slack).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(content().string(not(containsString(SLACK_TOKEN))));
    }

    @Test
    void updateResponseMasksTheUrlOfASlackWebhook() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.update(eq(id), eq(webhookId), any())).thenReturn(slackEndpoint(id, webhookId));

        mockMvc.perform(json(put(WEBHOOK, id, webhookId), user(id),
                        "{\"url\": \"" + SLACK_MASK + "\", \"events\": [\"task.assigned\"], \"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"))
                .andExpect(jsonPath("$.url").value(SLACK_MASK))
                .andExpect(content().string(not(containsString(SLACK_TOKEN))));

        ArgumentCaptor<UpdateWebhookEndpointDto> dto = ArgumentCaptor.forClass(UpdateWebhookEndpointDto.class);
        verify(webhookService).update(eq(id), eq(webhookId), dto.capture());
        assertThat(dto.getValue().url()).isEqualTo(SLACK_MASK);
    }

    @Test
    void kindInAnUpdateBodyIsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.update(eq(id), eq(webhookId), any())).thenReturn(slackEndpoint(id, webhookId));

        mockMvc.perform(json(put(WEBHOOK, id, webhookId), user(id),
                        "{\"kind\": \"WEBHOOK\", \"url\": \"" + URL + "\", \"events\": [\"task.assigned\"], "
                                + "\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SLACK"));

        ArgumentCaptor<UpdateWebhookEndpointDto> dto = ArgumentCaptor.forClass(UpdateWebhookEndpointDto.class);
        verify(webhookService).update(eq(id), eq(webhookId), dto.capture());
        assertThat(dto.getValue().url()).isEqualTo(URL);
    }

    @Test
    void listPassesThePathIdAndShowsNoSecret() throws Exception {
        UUID id = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(webhookService.findAll(id)).thenReturn(List.of(endpoint(id, first), endpoint(id, second)));

        mockMvc.perform(get(WEBHOOKS, id).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(first.toString(), second.toString())))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].previousSecret").doesNotExist());
    }

    @Test
    void getPassesBothIds() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.get(id, webhookId)).thenReturn(endpoint(id, webhookId));

        mockMvc.perform(get(WEBHOOK, id, webhookId).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.secret").doesNotExist());

        verify(webhookService).get(id, webhookId);
    }

    @Test
    void updatePassesBothIdsAndTheParsedBody() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.update(eq(id), eq(webhookId), any())).thenReturn(endpoint(id, webhookId));

        mockMvc.perform(json(put(WEBHOOK, id, webhookId), user(id), UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.secret").doesNotExist());

        ArgumentCaptor<UpdateWebhookEndpointDto> dto = ArgumentCaptor.forClass(UpdateWebhookEndpointDto.class);
        verify(webhookService).update(eq(id), eq(webhookId), dto.capture());
        assertThat(dto.getValue().url()).isEqualTo(URL);
        assertThat(dto.getValue().events()).containsExactly(WebhookEvent.TASK_DUE_SOON);
        assertThat(dto.getValue().enabled()).isFalse();
    }

    @Test
    void deletePassesBothIdsAndReturnsNoContent() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();

        mockMvc.perform(delete(WEBHOOK, id, webhookId).with(user(id)))
                .andExpect(status().isNoContent());

        verify(webhookService).delete(id, webhookId);
    }

    @Test
    void rotationPassesBothIdsAndReturnsTheNewSecretWithTheExpiry() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-05-07T07:08:09Z");
        when(webhookService.rotateSecret(id, webhookId)).thenReturn(new RotatedWebhookSecret("whsec_new", expiry));

        mockMvc.perform(post(SECRET, id, webhookId).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("whsec_new"))
                .andExpect(jsonPath("$.previousSecretExpiresAt").value(expiry.toString()));

        verify(webhookService).rotateSecret(id, webhookId);
    }

    // Deliveries

    @Test
    void deliveriesPassBothIdsAndDefaultToTenNewestFirstWithTheIdBreakingTies() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any())).thenReturn(Page.empty());

        mockMvc.perform(get(DELIVERIES, id, webhookId).with(user(id)))
                .andExpect(status().isOk());

        Pageable pageable = requestedDeliveryPage(id, webhookId);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @Test
    void deliveriesPassThePagingParameters() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any())).thenReturn(Page.empty());

        mockMvc.perform(get(DELIVERIES, id, webhookId)
                        .param("page", "3")
                        .param("size", "5")
                        .param("sort", "attempts,asc")
                        .with(user(id)))
                .andExpect(status().isOk());

        Pageable pageable = requestedDeliveryPage(id, webhookId);
        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "attempts"));
    }

    @Test
    void deliveriesPageSizeAbove100IsClamped() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any())).thenReturn(Page.empty());

        mockMvc.perform(get(DELIVERIES, id, webhookId).param("size", "500").with(user(id)))
                .andExpect(status().isOk());

        assertThat(requestedDeliveryPage(id, webhookId).getPageSize()).isEqualTo(100);
    }

    @Test
    void deliveriesAreAPagedModelWithTheirAttemptsButNeitherPayloadNorEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        WebhookDelivery pending = delivery(WebhookDeliveryStatus.PENDING);
        pending.setAttempts(2);
        pending.setLastStatusCode(503);
        pending.setLastAttemptAt(CREATED_AT.plusSeconds(10));
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any()))
                .thenReturn(new PageImpl<>(List.of(pending), PageRequest.of(1, 1), 3));

        mockMvc.perform(get(DELIVERIES, id, webhookId).param("page", "1").param("size", "1").with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pending.getId().toString()))
                .andExpect(jsonPath("$.content[0].event").value("task.overdue"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].attempts").value(2))
                .andExpect(jsonPath("$.content[0].lastStatusCode").value(503))
                .andExpect(jsonPath("$.content[0].lastError").value(nullValue()))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").value(CREATED_AT.plusSeconds(310).toString()))
                .andExpect(jsonPath("$.content[0].lastAttemptAt").value(CREATED_AT.plusSeconds(10).toString()))
                .andExpect(jsonPath("$.content[0].deliveredAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.content[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @ParameterizedTest
    @EnumSource(value = WebhookDeliveryStatus.class, names = {"DELIVERED", "FAILED"})
    void finishedDeliveryShowsNoNextAttempt(WebhookDeliveryStatus status) throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any()))
                .thenReturn(new PageImpl<>(List.of(delivery(status))));

        mockMvc.perform(get(DELIVERIES, id, webhookId).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value(status.name()))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").value(nullValue()));
    }

    @Test
    void adminPassesTheCheckForTheDeliveriesOfAnotherUsersWebhook() throws Exception {
        UUID other = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(other), eq(webhookId), any())).thenReturn(Page.empty());

        mockMvc.perform(get(DELIVERIES, other, webhookId).with(admin(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        verify(webhookService).findDeliveries(eq(other), eq(webhookId), any());
    }

    @Test
    void deliveriesOfAWebhookTheServiceCannotFindAreANotFoundProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.findDeliveries(eq(id), eq(webhookId), any()))
                .thenThrow(new WebhookEndpointNotFoundException(webhookId));

        mockMvc.perform(get(DELIVERIES, id, webhookId).with(user(id)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + webhookId));
    }

    // Redelivery

    @Test
    void redeliverPassesTheThreeIdsAndAnswersAcceptedWithTheRequeuedDelivery() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        WebhookDelivery requeued = delivery(WebhookDeliveryStatus.PENDING);
        requeued.setAttempts(0);
        requeued.setLastStatusCode(500);
        when(webhookService.redeliver(id, webhookId, requeued.getId())).thenReturn(requeued);

        mockMvc.perform(post(REDELIVER, id, webhookId, requeued.getId()).with(user(id)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(requeued.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.lastStatusCode").value(500))
                .andExpect(jsonPath("$.nextAttemptAt").value(CREATED_AT.plusSeconds(310).toString()))
                .andExpect(jsonPath("$.deliveredAt").value(nullValue()))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.endpoint").doesNotExist());

        verify(webhookService).redeliver(id, webhookId, requeued.getId());
    }

    @Test
    void adminPassesTheCheckToRedeliverForAnotherUser() throws Exception {
        UUID other = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        WebhookDelivery requeued = delivery(WebhookDeliveryStatus.PENDING);
        when(webhookService.redeliver(other, webhookId, requeued.getId())).thenReturn(requeued);

        mockMvc.perform(post(REDELIVER, other, webhookId, requeued.getId()).with(admin(UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(requeued.getId().toString()));

        verify(webhookService).redeliver(other, webhookId, requeued.getId());
    }

    @Test
    void deliveryTheServiceCannotFindIsANotFoundProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        when(webhookService.redeliver(id, webhookId, deliveryId))
                .thenThrow(new WebhookDeliveryNotFoundException(deliveryId));

        mockMvc.perform(post(REDELIVER, id, webhookId, deliveryId).with(user(id)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook delivery not found with id: " + deliveryId));
    }

    @Test
    void redeliveryUnderAWebhookTheServiceCannotFindIsANotFoundProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        when(webhookService.redeliver(id, webhookId, deliveryId))
                .thenThrow(new WebhookEndpointNotFoundException(webhookId));

        mockMvc.perform(post(REDELIVER, id, webhookId, deliveryId).with(user(id)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + webhookId));
    }

    @Test
    void deliveryIdThatIsNotAUuidIsAnInvalidParameter() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post(REDELIVER, id, UUID.randomUUID(), "not-a-uuid").with(user(id)))
                .andExpect(invalidParameter("deliveryId", "must be a UUID"));

        verifyNoInteractions(webhookService);
    }

    // Test events

    @Test
    void testPassesBothIdsAndReturnsHowTheReceiverAnswered() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.sendTest(id, webhookId)).thenReturn(new WebhookTestResult(true, 204, null, 42));

        mockMvc.perform(post(TEST, id, webhookId).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.statusCode").value(204))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.durationMillis").value(42));

        verify(webhookService).sendTest(id, webhookId);
    }

    @Test
    void testWithoutAnAnswerShowsTheErrorAndNoStatus() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.sendTest(id, webhookId))
                .thenReturn(new WebhookTestResult(false, null, "Timeout", 15001));

        mockMvc.perform(post(TEST, id, webhookId).with(user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(nullValue()))
                .andExpect(jsonPath("$.error").value("Timeout"))
                .andExpect(jsonPath("$.durationMillis").value(15001));
    }

    @Test
    void adminPassesTheCheckToTestAnotherUsersWebhook() throws Exception {
        UUID other = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.sendTest(other, webhookId)).thenReturn(new WebhookTestResult(false, 500, null, 7));

        mockMvc.perform(post(TEST, other, webhookId).with(admin(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.statusCode").value(500));

        verify(webhookService).sendTest(other, webhookId);
    }

    @Test
    void testPastTheRateLimitIsATooManyRequestsProblemWithRetryAfter() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.sendTest(id, webhookId)).thenThrow(new RateLimitExceededException(Duration.ofMinutes(20)));

        mockMvc.perform(post(TEST, id, webhookId).with(user(id)))
                .andExpect(untypedProblem(429, "Too Many Requests"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1200"))
                .andExpect(jsonPath("$.detail").value("Too many requests, try again in 1200 seconds"));
    }

    @Test
    void testOfAWebhookTheServiceCannotFindIsANotFoundProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.sendTest(id, webhookId)).thenThrow(new WebhookEndpointNotFoundException(webhookId));

        mockMvc.perform(post(TEST, id, webhookId).with(user(id)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + webhookId));
    }

    // Validation

    @Test
    void createWithoutEventsPointsToThem() throws Exception {
        expectCreateRejected("{\"url\": \"" + URL + "\"}")
                .andExpect(invalidBodyValue("#/events", "must not be empty"));
    }

    @Test
    void createWithAnEmptyEventListPointsToIt() throws Exception {
        expectCreateRejected("{\"url\": \"" + URL + "\", \"events\": []}")
                .andExpect(invalidBodyValue("#/events", "must not be empty"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"task.exploded\"", "\"TASK_ASSIGNED\"", "\"assigned\"", "null"})
    void createWithAnUnknownOrNullEventPointsToIt(String event) throws Exception {
        expectCreateRejected("{\"url\": \"" + URL + "\", \"events\": [" + event + "]}")
                .andExpect(validationError())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/events/0"))
                .andExpect(withoutJavaTypeNames());
    }

    @Test
    void createWithEventsThatAreNotAListPointsToThem() throws Exception {
        expectCreateRejected("{\"url\": \"" + URL + "\", \"events\": 42}")
                .andExpect(validationError())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/events"))
                .andExpect(withoutJavaTypeNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    void createWithABlankUrlPointsToIt(String url) throws Exception {
        expectCreateRejected("{\"url\": " + url + ", \"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/url", "must not be blank"));
    }

    @Test
    void createWithoutUrlPointsToIt() throws Exception {
        expectCreateRejected("{\"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/url", "must not be blank"));
    }

    @Test
    void createWithAUrlLongerThan2048CharactersPointsToIt() throws Exception {
        String url = "https://hooks.example.com/" + "a".repeat(2048);

        expectCreateRejected("{\"url\": \"" + url + "\", \"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/url", "size must be between 0 and 2048"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"TEAMS\"", "\"slack\"", "\"\"", "true", "{}"})
    void createWithAnUnknownKindPointsToIt(String kind) throws Exception {
        expectCreateRejected("{\"kind\": " + kind + ", \"url\": \"" + URL + "\", \"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/kind", "must be one of WEBHOOK, SLACK"))
                .andExpect(withoutJavaTypeNames());
    }

    @Test
    void createWithMalformedJsonReturnsBadRequest() throws Exception {
        expectCreateRejected("{\"url\": ");
    }

    @Test
    void updateWithoutEnabledPointsToIt() throws Exception {
        expectUpdateRejected("{\"url\": \"" + URL + "\", \"events\": [\"task.assigned\"]}")
                .andExpect(invalidBodyValue("#/enabled", "must not be null"));
    }

    @Test
    void updateWithEnabledThatIsNotABooleanPointsToIt() throws Exception {
        expectUpdateRejected("{\"url\": \"" + URL + "\", \"events\": [\"task.assigned\"], \"enabled\": \"maybe\"}")
                .andExpect(invalidBodyValue("#/enabled", "must be true or false"));
    }

    @Test
    void updateWithoutEventsPointsToThem() throws Exception {
        expectUpdateRejected("{\"url\": \"" + URL + "\", \"events\": [], \"enabled\": true}")
                .andExpect(invalidBodyValue("#/events", "must not be empty"));
    }

    @Test
    void updateWithABlankUrlPointsToIt() throws Exception {
        expectUpdateRejected("{\"url\": \" \", \"events\": [\"task.assigned\"], \"enabled\": true}")
                .andExpect(invalidBodyValue("#/url", "must not be blank"));
    }

    @Test
    void userIdThatIsNotAUuidIsAnInvalidParameter() throws Exception {
        mockMvc.perform(get(WEBHOOKS, "not-a-uuid").with(admin(UUID.randomUUID())))
                .andExpect(invalidParameter("id", "must be a UUID"));

        verifyNoInteractions(webhookService);
    }

    @Test
    void webhookIdThatIsNotAUuidIsAnInvalidParameter() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get(WEBHOOK, id, "not-a-uuid").with(user(id)))
                .andExpect(invalidParameter("webhookId", "must be a UUID"));
        mockMvc.perform(post(SECRET, id, "not-a-uuid").with(user(id)))
                .andExpect(invalidParameter("webhookId", "must be a UUID"));
        mockMvc.perform(get(DELIVERIES, id, "not-a-uuid").with(user(id)))
                .andExpect(invalidParameter("webhookId", "must be a UUID"));
        mockMvc.perform(post(REDELIVER, id, "not-a-uuid", UUID.randomUUID()).with(user(id)))
                .andExpect(invalidParameter("webhookId", "must be a UUID"));
        mockMvc.perform(post(TEST, id, "not-a-uuid").with(user(id)))
                .andExpect(invalidParameter("webhookId", "must be a UUID"));

        verifyNoInteractions(webhookService);
    }

    // Exception mapping

    @Test
    void webhookTheServiceCannotFindIsANotFoundProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.get(id, webhookId)).thenThrow(new WebhookEndpointNotFoundException(webhookId));

        mockMvc.perform(get(WEBHOOK, id, webhookId).with(user(id)))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("Webhook not found with id: " + webhookId));
    }

    @Test
    void userTheServiceCannotFindIsANotFoundProblem() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(webhookService.findAll(unknown)).thenThrow(new UserNotFoundException(unknown));

        mockMvc.perform(get(WEBHOOKS, unknown).with(admin(UUID.randomUUID())))
                .andExpect(untypedProblem(404, "Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found with id: " + unknown));
    }

    @Test
    void refusedUrlOnCreationIsAWebhookUrlNotAllowedProblem() throws Exception {
        UUID id = UUID.randomUUID();
        when(webhookService.create(eq(id), any()))
                .thenThrow(new WebhookUrlNotAllowedException("The URL must point to a public address"));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id), CREATE_BODY))
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value("The URL must point to a public address"));
    }

    @Test
    void refusedUrlOnUpdateIsAWebhookUrlNotAllowedProblem() throws Exception {
        UUID id = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        when(webhookService.update(eq(id), eq(webhookId), any()))
                .thenThrow(new WebhookUrlNotAllowedException("The URL must use HTTPS on port 443"));

        mockMvc.perform(json(put(WEBHOOK, id, webhookId), user(id), UPDATE_BODY))
                .andExpect(typedProblem(422, "webhook-url-not-allowed", "Webhook URL not allowed"))
                .andExpect(jsonPath("$.detail").value("The URL must use HTTPS on port 443"));
    }

    @Test
    void limitReachedIsAWebhookLimitReachedProblem() throws Exception {
        UUID id = UUID.randomUUID();
        when(webhookService.create(eq(id), any())).thenThrow(new WebhookLimitReachedException(5));

        mockMvc.perform(json(post(WEBHOOKS, id), user(id), CREATE_BODY))
                .andExpect(typedProblem(422, "webhook-limit-reached", "Webhook limit reached"))
                .andExpect(jsonPath("$.detail").value("A user can declare at most 5 webhooks"));
    }

    private ResultActions expectCreateRejected(String body) throws Exception {
        UUID id = UUID.randomUUID();

        ResultActions result = mockMvc.perform(json(post(WEBHOOKS, id), user(id), body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookService);
        return result;
    }

    private ResultActions expectUpdateRejected(String body) throws Exception {
        UUID id = UUID.randomUUID();

        ResultActions result = mockMvc.perform(json(put(WEBHOOK, id, UUID.randomUUID()), user(id), body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookService);
        return result;
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request,
            RequestPostProcessor caller,
            String body
    ) {
        return request.with(caller).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<MockHttpServletRequestBuilder> everyEndpoint(UUID id, UUID webhookId) {
        return List.of(
                post(WEBHOOKS, id).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
                get(WEBHOOKS, id),
                get(WEBHOOK, id, webhookId),
                put(WEBHOOK, id, webhookId).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
                delete(WEBHOOK, id, webhookId),
                post(SECRET, id, webhookId),
                get(DELIVERIES, id, webhookId),
                post(REDELIVER, id, webhookId, UUID.randomUUID()),
                post(TEST, id, webhookId)
        );
    }

    private Pageable requestedDeliveryPage(UUID id, UUID webhookId) {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(webhookService).findDeliveries(eq(id), eq(webhookId), pageable.capture());
        return pageable.getValue();
    }

    private static WebhookDelivery delivery(WebhookDeliveryStatus status) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setEvent(WebhookEvent.TASK_OVERDUE);
        delivery.setPayload("{\"type\":\"task.overdue\"}");
        delivery.setStatus(status);
        delivery.setAttempts(1);
        delivery.setNextAttemptAt(CREATED_AT.plusSeconds(310));
        delivery.setCreatedAt(CREATED_AT);
        return delivery;
    }

    private static WebhookEndpoint slackEndpoint(UUID userId, UUID webhookId) {
        WebhookEndpoint endpoint = endpoint(userId, webhookId);
        endpoint.setKind(WebhookKind.SLACK);
        endpoint.setUrl(SLACK_URL);
        return endpoint;
    }

    private static WebhookEndpoint endpoint(UUID userId, UUID webhookId) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(webhookId);
        endpoint.setUser(UserProfiles.reference(userId));
        endpoint.setUrl(URL);
        endpoint.setSecret("v1:encrypted");
        endpoint.setPreviousSecret("v1:previous");
        endpoint.setEvents(EnumSet.of(WebhookEvent.TASK_OVERDUE, WebhookEvent.TASK_ASSIGNED));
        endpoint.setCreatedAt(CREATED_AT);
        endpoint.setUpdatedAt(CREATED_AT);
        return endpoint;
    }
}
