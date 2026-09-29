package io.julienmetral.tasks.export.controllers;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.export.exceptions.DataExportInProgressException;
import io.julienmetral.tasks.export.exceptions.DataExportNotFoundException;
import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.entities.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.invalidBodyValue;
import static io.julienmetral.tasks.support.Problems.invalidParameter;
import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.Problems.withoutJavaTypeNames;
import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static io.julienmetral.tasks.support.WebCallers.withoutUid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class DataExportControllerWebMvcTests {

    private static final String EXPORTS = "/api/v1/exports";

    private static final String TASKS_EXPORT = EXPORTS + "/tasks";

    private static final String USERS_EXPORT = EXPORTS + "/users";

    private static final String EXPORT = EXPORTS + "/{id}";

    private static final Instant CREATED_AT = Instant.parse("2030-01-01T08:00:00Z");

    private static final Instant COMPLETED_AT = Instant.parse("2030-01-01T08:01:00Z");

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-08T08:01:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataExportService exportService;

    @Autowired
    private MediaUrls mediaUrls;

    @Autowired
    private UserRepository userRepository;

    private final UUID caller = UUID.randomUUID();

    private final UUID exportId = UUID.randomUUID();

    private static List<MockHttpServletRequestBuilder> everyEndpoint(UUID exportId) {
        return List.of(
                post(TASKS_EXPORT),
                post(USERS_EXPORT),
                get(EXPORTS),
                get(EXPORT, exportId),
                delete(EXPORT, exportId)
        );
    }

    private DataExport export(DataExportType type, DataExportStatus status, TaskExportFilters filters) {
        DataExport export = new DataExport();
        export.setId(exportId);
        export.setType(type);
        export.setStatus(status);
        export.setTaskFilters(filters);
        export.setCreatedAt(CREATED_AT);
        return export;
    }

    private DataExport completedTasksExport(Media media) {
        DataExport export = export(DataExportType.TASKS_CSV, DataExportStatus.COMPLETED,
                new TaskExportFilters(null, null, false));
        export.setMedia(media);
        export.setRowCount(3L);
        export.setCompletedAt(COMPLETED_AT);
        export.setExpiresAt(EXPIRES_AT);
        return export;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    class Access {

        @Test
        void everyEndpointWithoutTokenIsUnauthorized() throws Exception {
            for (MockHttpServletRequestBuilder request : everyEndpoint(exportId)) {
                mockMvc.perform(request).andExpect(status().isUnauthorized());
            }

            verifyNoInteractions(exportService);
        }

        @Test
        void disabledAccountIsForbiddenOnEveryEndpointEvenForAnAdmin() throws Exception {
            when(userRepository.findAccountStateById(caller))
                    .thenReturn(Optional.of(new AccountState(false, Instant.parse("2026-01-01T00:00:00Z"))));

            for (MockHttpServletRequestBuilder request : everyEndpoint(exportId)) {
                mockMvc.perform(request.with(admin(caller))).andExpect(status().isForbidden());
            }

            verifyNoInteractions(exportService);
        }

        @Test
        void accountWithoutVerifiedEmailIsForbiddenOnEveryEndpoint() throws Exception {
            when(userRepository.findAccountStateById(caller)).thenReturn(Optional.of(new AccountState(true, null)));

            for (MockHttpServletRequestBuilder request : everyEndpoint(exportId)) {
                mockMvc.perform(request.with(user(caller))).andExpect(status().isForbidden());
            }

            verifyNoInteractions(exportService);
        }

        @Test
        void deletedAccountIsForbiddenOnEveryEndpoint() throws Exception {
            when(userRepository.findAccountStateById(caller)).thenReturn(Optional.empty());

            for (MockHttpServletRequestBuilder request : everyEndpoint(exportId)) {
                mockMvc.perform(request.with(user(caller))).andExpect(status().isForbidden());
            }

            verifyNoInteractions(exportService);
        }

        @Test
        void tokenWithoutUidClaimIsForbiddenOnEveryEndpoint() throws Exception {
            for (MockHttpServletRequestBuilder request : everyEndpoint(exportId)) {
                mockMvc.perform(request.with(withoutUid())).andExpect(status().isForbidden());
            }

            verifyNoInteractions(userRepository, exportService);
        }

        @Test
        void memberCannotExportTheUsers() throws Exception {
            everyAccountIsActive(userRepository);

            mockMvc.perform(post(USERS_EXPORT).with(user(caller)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(exportService);
        }
    }

    @Nested
    class TasksExport {

        @BeforeEach
        void callersAreActive() {
            everyAccountIsActive(userRepository);
        }

        @Test
        void requestIsAcceptedWithTheQueuedExportAndItsLocation() throws Exception {
            UUID assignee = UUID.randomUUID();
            TaskExportFilters filters = new TaskExportFilters(TaskStatus.IN_PROGRESS, assignee, true);
            when(exportService.request(caller, DataExportType.TASKS_CSV, filters))
                    .thenReturn(export(DataExportType.TASKS_CSV, DataExportStatus.QUEUED, filters));

            mockMvc.perform(json(post(TASKS_EXPORT).with(user(caller)), """
                            {"status": "IN_PROGRESS", "assigneeId": "%s", "archived": true}
                            """.formatted(assignee)))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/exports/" + exportId))
                    .andExpect(jsonPath("$.id").value(exportId.toString()))
                    .andExpect(jsonPath("$.type").value("TASKS_CSV"))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.filters.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.filters.assigneeId").value(assignee.toString()))
                    .andExpect(jsonPath("$.filters.archived").value(true))
                    .andExpect(jsonPath("$.rowCount").value(nullValue()))
                    .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                    .andExpect(jsonPath("$.completedAt").value(nullValue()))
                    .andExpect(jsonPath("$.downloadUrl").value(nullValue()));

            verifyNoInteractions(mediaUrls);
        }

        @Test
        void requestWithoutBodyExportsEveryTaskThatIsNotArchived() throws Exception {
            TaskExportFilters everyActiveTask = new TaskExportFilters(null, null, false);
            when(exportService.request(caller, DataExportType.TASKS_CSV, everyActiveTask))
                    .thenReturn(export(DataExportType.TASKS_CSV, DataExportStatus.QUEUED, everyActiveTask));

            mockMvc.perform(post(TASKS_EXPORT).with(user(caller)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.filters.archived").value(false));

            verify(exportService).request(caller, DataExportType.TASKS_CSV, everyActiveTask);
        }

        @Test
        void emptyBodyExportsEveryTaskThatIsNotArchived() throws Exception {
            TaskExportFilters everyActiveTask = new TaskExportFilters(null, null, false);
            when(exportService.request(caller, DataExportType.TASKS_CSV, everyActiveTask))
                    .thenReturn(export(DataExportType.TASKS_CSV, DataExportStatus.QUEUED, everyActiveTask));

            mockMvc.perform(json(post(TASKS_EXPORT).with(user(caller)), "{}"))
                    .andExpect(status().isAccepted());

            verify(exportService).request(caller, DataExportType.TASKS_CSV, everyActiveTask);
        }

        @Test
        void unknownStatusPointsToItAndListsTheStatuses() throws Exception {
            expectRejected("{\"status\": \"FINISHED\"}")
                    .andExpect(invalidBodyValue("#/status",
                            "must be one of TO_DO, IN_PROGRESS, IN_REVIEW, BLOCKED, DONE, ARCHIVED, CANCELLED"))
                    .andExpect(withoutJavaTypeNames());
        }

        @Test
        void malformedAssigneeIdPointsToIt() throws Exception {
            expectRejected("{\"assigneeId\": \"not-a-uuid\"}")
                    .andExpect(invalidBodyValue("#/assigneeId", "must be a UUID"))
                    .andExpect(withoutJavaTypeNames());
        }

        @Test
        void archivedFlagThatIsNotABooleanPointsToIt() throws Exception {
            expectRejected("{\"archived\": \"maybe\"}")
                    .andExpect(invalidBodyValue("#/archived", "must be true or false"))
                    .andExpect(withoutJavaTypeNames());
        }

        @Test
        void exportOfTheSameTypeInProgressIsATypedConflict() throws Exception {
            when(exportService.request(eq(caller), eq(DataExportType.TASKS_CSV), any()))
                    .thenThrow(new DataExportInProgressException());

            mockMvc.perform(post(TASKS_EXPORT).with(user(caller)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"))
                    .andExpect(jsonPath("$.detail")
                            .value("An export of the same kind is already queued or running: wait for it to finish"))
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        }

        private ResultActions expectRejected(String body) throws Exception {
            ResultActions result = mockMvc.perform(json(post(TASKS_EXPORT).with(user(caller)), body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(exportService);
            return result;
        }
    }

    @Nested
    class UsersExport {

        @BeforeEach
        void callersAreActive() {
            everyAccountIsActive(userRepository);
        }

        @Test
        void adminRequestIsAcceptedWithTheQueuedExportAndItsLocation() throws Exception {
            when(exportService.request(caller, DataExportType.USERS_CSV, null))
                    .thenReturn(export(DataExportType.USERS_CSV, DataExportStatus.QUEUED, null));

            mockMvc.perform(post(USERS_EXPORT).with(admin(caller)))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/exports/" + exportId))
                    .andExpect(jsonPath("$.type").value("USERS_CSV"))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.filters").value(nullValue()));
        }

        @Test
        void usersExportInProgressIsATypedConflict() throws Exception {
            when(exportService.request(caller, DataExportType.USERS_CSV, null))
                    .thenThrow(new DataExportInProgressException());

            mockMvc.perform(post(USERS_EXPORT).with(admin(caller)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"));
        }
    }

    @Nested
    class OwnExports {

        @BeforeEach
        void callersAreActive() {
            everyAccountIsActive(userRepository);
        }

        @Test
        void listIsThePageOfTheCallerNewestFirstByDefault() throws Exception {
            when(exportService.findOwn(eq(caller), any())).thenAnswer(invocation -> new PageImpl<>(
                    List.of(export(DataExportType.USERS_CSV, DataExportStatus.FAILED, null)),
                    invocation.getArgument(1),
                    1));

            mockMvc.perform(get(EXPORTS).with(user(caller)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(exportId.toString()))
                    .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                    .andExpect(jsonPath("$.page.totalElements").value(1))
                    .andExpect(jsonPath("$.page.number").value(0));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(exportService).findOwn(eq(caller), pageable.capture());
            assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        @Test
        void listPassesTheRequestedPage() throws Exception {
            when(exportService.findOwn(eq(caller), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get(EXPORTS).param("page", "2").param("size", "5").with(user(caller)))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(exportService).findOwn(eq(caller), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        @Test
        void completedExportCarriesItsDownloadLink() throws Exception {
            Media media = new Media();
            when(exportService.getOwn(caller, exportId)).thenReturn(completedTasksExport(media));
            when(mediaUrls.of(media)).thenReturn("https://storage.example/export/key?X-Amz-Signature=abc");

            mockMvc.perform(get(EXPORT, exportId).with(user(caller)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.rowCount").value(3))
                    .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()))
                    .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()))
                    .andExpect(jsonPath("$.downloadUrl")
                            .value("https://storage.example/export/key?X-Amz-Signature=abc"));
        }

        @Test
        void exportStillRunningHasNoDownloadLink() throws Exception {
            when(exportService.getOwn(caller, exportId))
                    .thenReturn(export(DataExportType.TASKS_CSV, DataExportStatus.RUNNING, null));

            mockMvc.perform(get(EXPORT, exportId).with(user(caller)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.downloadUrl").value(nullValue()));

            verifyNoInteractions(mediaUrls);
        }

        @Test
        void exportOfSomeoneElseIsAnUntypedNotFound() throws Exception {
            when(exportService.getOwn(caller, exportId)).thenThrow(new DataExportNotFoundException(exportId));

            mockMvc.perform(get(EXPORT, exportId).with(user(caller)))
                    .andExpect(untypedProblem(404, "Not Found"))
                    .andExpect(jsonPath("$.detail").value("Export not found: " + exportId));
        }

        @Test
        void malformedIdNamesThePathParameter() throws Exception {
            mockMvc.perform(get(EXPORTS + "/not-a-uuid").with(user(caller)))
                    .andExpect(invalidParameter("id", "must be a UUID"))
                    .andExpect(withoutJavaTypeNames());

            verifyNoInteractions(exportService);
        }

        @Test
        void deleteAnswersNoContent() throws Exception {
            mockMvc.perform(delete(EXPORT, exportId).with(user(caller)))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(exportService).deleteOwn(caller, exportId);
        }

        @Test
        void deletingAnExportInProgressIsATypedConflict() throws Exception {
            doThrow(new DataExportInProgressException())
                    .when(exportService).deleteOwn(caller, exportId);

            mockMvc.perform(delete(EXPORT, exportId).with(user(caller)))
                    .andExpect(typedProblem(409, "export-in-progress", "Export already in progress"));
        }

        @Test
        void deletingTheExportOfSomeoneElseIsAnUntypedNotFound() throws Exception {
            doThrow(new DataExportNotFoundException(exportId))
                    .when(exportService).deleteOwn(caller, exportId);

            mockMvc.perform(delete(EXPORT, exportId).with(user(caller)))
                    .andExpect(untypedProblem(404, "Not Found"));
        }
    }
}
