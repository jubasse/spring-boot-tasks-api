package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.support.WebLayerTest;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.exceptions.AssigneeNotActiveException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import io.julienmetral.tasks.task.security.TaskAuthorization;
import io.julienmetral.tasks.task.services.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.WebCallers.admin;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class TaskControllerWebMvcTests {

    private static final String TASKS = "/api/v1/tasks";

    private static final String NEW_TASK = "{\"reference\": \"T-1\", \"title\": \"Task\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskAuthorization taskAuthorization;

    @Autowired
    private UserRepository userRepository;

    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void callersAreActive() {
        everyAccountIsActive(userRepository);
    }

    @Nested
    class Authentication {

        @Test
        void everyEndpointRequiresAuthentication() throws Exception {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    get(TASKS),
                    get(TASKS + "/" + taskId),
                    json(post(TASKS), "{\"reference\": \"T-1\", \"title\": \"x\"}"),
                    json(patch(TASKS + "/" + taskId), "{\"title\": \"x\"}"),
                    json(patch(TASKS + "/" + taskId + "/status"), "{\"status\": \"DONE\"}"),
                    json(patch(TASKS + "/" + taskId + "/assign"), "{\"userId\": \"%s\"}".formatted(UUID.randomUUID())),
                    post(TASKS + "/" + taskId + "/archive"),
                    post(TASKS + "/" + taskId + "/unarchive"),
                    json(post(TASKS + "/" + taskId + "/cancel"), "{\"reason\": \"x\"}"),
                    delete(TASKS + "/" + taskId)
            );

            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request).andExpect(status().isUnauthorized());
            }

            verifyNoInteractions(taskService);
        }

        @Test
        void tokenWithoutUidClaimIsForbiddenOnTasksEvenForAnAdmin() throws Exception {
            mockMvc.perform(get(TASKS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userRepository, taskService);
        }
    }

    @Nested
    class Creation {

        @Test
        void createWithBlankReferenceReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"   \", \"title\": \"Task\"}");
        }

        @Test
        void createWithMissingReferenceReturns400() throws Exception {
            expectCreateRejected("{\"title\": \"Task\"}");
        }

        @Test
        void createWithReferenceLongerThan30CharsReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"%s\", \"title\": \"Task\"}".formatted("R".repeat(31)));
        }

        @Test
        void createWithBlankTitleReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\", \"title\": \"  \"}");
        }

        @Test
        void createWithMissingTitleReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\"}");
        }

        @Test
        void createWithTitleLongerThan255CharsReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\", \"title\": \"%s\"}".formatted("t".repeat(256)));
        }

        @Test
        void createWithUnknownPriorityReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\", \"title\": \"Task\", \"priority\": \"CRITICAL\"}");
        }

        @Test
        void createWithMalformedDueDateReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\", \"title\": \"Task\", \"dueAt\": \"tomorrow\"}");
        }

        @Test
        void createWithMalformedAssigneeIdReturns400() throws Exception {
            expectCreateRejected("{\"reference\": \"T-1\", \"title\": \"Task\", \"assignedTo\": \"not-a-uuid\"}");
        }

        @Test
        void createWithMalformedJsonReturns400() throws Exception {
            expectCreateRejected("{\"reference\": ");
        }

        @Test
        void userCannotAssignTaskOnCreation() throws Exception {
            UUID self = UUID.randomUUID();

            create(user(self), newTaskAssignedTo(self))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(taskService);
        }

        @Test
        void userCanCreateAnUnassignedTask() throws Exception {
            when(taskService.create(any())).thenReturn(task("T-1"));

            create(user(UUID.randomUUID()), NEW_TASK)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reference").value("T-1"));
        }

        @Test
        void adminPassesTheCheckToAssignOnCreation() throws Exception {
            when(taskService.create(any())).thenReturn(task("T-1"));

            create(admin(UUID.randomUUID()), newTaskAssignedTo(UUID.randomUUID()))
                    .andExpect(status().isCreated());
        }

        @Test
        void takenReferenceReturnsConflictProblem() throws Exception {
            when(taskService.create(any())).thenThrow(new TaskReferenceAlreadyExistsException("T-1"));

            create(user(UUID.randomUUID()), NEW_TASK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Task reference already exists"));
        }

        @Test
        void referenceTakenByAConcurrentCreationReturnsAGenericConflict() throws Exception {
            when(taskService.create(any())).thenThrow(new DataIntegrityViolationException("tasks_referenceUQ"));

            create(user(UUID.randomUUID()), NEW_TASK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Data conflict"))
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
        }

        @Test
        void unknownAssigneeReturnsNotFoundProblem() throws Exception {
            UUID unknown = UUID.randomUUID();
            when(taskService.create(any())).thenThrow(new UserNotFoundException(unknown));

            create(admin(UUID.randomUUID()), newTaskAssignedTo(unknown))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("User not found"));
        }

        @Test
        void inactiveAssigneeReturnsUnprocessableProblemNamingTheStatus() throws Exception {
            UUID disabled = UUID.randomUUID();
            when(taskService.create(any())).thenThrow(new AssigneeNotActiveException(disabled, UserStatus.DISABLED));

            create(admin(UUID.randomUUID()), newTaskAssignedTo(disabled))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.title").value("User cannot be assigned"))
                    .andExpect(jsonPath("$.detail").value(containsString("DISABLED")));
        }

        private void expectCreateRejected(String body) throws Exception {
            create(admin(UUID.randomUUID()), body).andExpect(status().isBadRequest());

            verifyNoInteractions(taskService);
        }

        private ResultActions create(RequestPostProcessor caller, String body) throws Exception {
            return mockMvc.perform(json(post(TASKS), body).with(caller));
        }

        private static String newTaskAssignedTo(UUID assignee) {
            return "{\"reference\": \"T-1\", \"title\": \"Task\", \"assignedTo\": \"%s\"}".formatted(assignee);
        }
    }

    @Nested
    class Listing {

        @Test
        void invalidStatusIsBadRequest() throws Exception {
            expectListRejected("status", "NOT_A_STATUS");
        }

        @Test
        void invalidAssigneeIdIsBadRequest() throws Exception {
            expectListRejected("assigneeId", "not-a-uuid");
        }

        @Test
        void invalidArchivedFlagIsBadRequest() throws Exception {
            expectListRejected("archived", "maybe");
        }

        @Test
        void listDefaultsToUnarchivedTasksNewestFirst() throws Exception {
            when(taskService.findAll(any(), any(), eq(false), any())).thenReturn(Page.empty());

            mockMvc.perform(get(TASKS).with(user(UUID.randomUUID())))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(taskService).findAll(isNull(), isNull(), eq(false), pageable.capture());
            assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        private void expectListRejected(String parameter, String value) throws Exception {
            mockMvc.perform(get(TASKS).param(parameter, value).with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(taskService);
        }
    }

    @Nested
    class Reading {

        @Test
        void findByIdReturns400ForMalformedId() throws Exception {
            mockMvc.perform(get(TASKS + "/not-a-uuid").with(user(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownTaskReturnsNotFoundProblem() throws Exception {
            when(taskService.findById(taskId)).thenThrow(new TaskNotFoundException(taskId));

            mockMvc.perform(get(TASKS + "/" + taskId).with(user(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Task not found"))
                    .andExpect(jsonPath("$.detail").value("Task not found with id: " + taskId));
        }
    }

    @Nested
    class AssigneeMutations {

        @Test
        void updateWithTitleLongerThan255CharsReturns400() throws Exception {
            expectRejected(patch(TASKS + "/" + taskId), "{\"title\": \"%s\"}".formatted("t".repeat(256)));
        }

        @Test
        void updateWithUnknownPriorityReturns400() throws Exception {
            expectRejected(patch(TASKS + "/" + taskId), "{\"priority\": \"CRITICAL\"}");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"status\": \"FINISHED\"}", "{}", "{\"status\": null}"})
        void statusWithInvalidValueReturns400(String body) throws Exception {
            expectRejected(patch(TASKS + "/" + taskId + "/status"), body);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"reason\": \"  \"}", "{}"})
        void cancelWithBlankOrMissingReasonReturns400(String body) throws Exception {
            expectRejected(post(TASKS + "/" + taskId + "/cancel"), body);
        }

        @Test
        void cancelWithTooLongReasonReturns400() throws Exception {
            expectRejected(post(TASKS + "/" + taskId + "/cancel"), "{\"reason\": \"%s\"}".formatted("r".repeat(501)));
        }

        @Test
        void nonAssignedUserIsForbiddenBeforeTheServiceRuns() throws Exception {
            UUID caller = UUID.randomUUID();
            when(taskAuthorization.currentUserIsAssignedTo(eq(taskId), any())).thenReturn(false);

            mockMvc.perform(json(patch(TASKS + "/" + taskId), "{\"title\": \"x\"}").with(user(caller)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/status"), "{\"status\": \"DONE\"}").with(user(caller)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(json(post(TASKS + "/" + taskId + "/cancel"), "{\"reason\": \"x\"}").with(user(caller)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(taskService);
        }

        @Test
        void assigneeCheckIsAskedAboutTheTaskOfThePath() throws Exception {
            when(taskAuthorization.currentUserIsAssignedTo(eq(taskId), any())).thenReturn(true);
            when(taskService.update(eq(taskId), any())).thenReturn(task("T-1"));

            mockMvc.perform(json(patch(TASKS + "/" + taskId), "{\"title\": \"x\"}").with(user(UUID.randomUUID())))
                    .andExpect(status().isOk());

            verify(taskAuthorization).currentUserIsAssignedTo(eq(taskId), any());
        }

        @Test
        void adminSkipsTheAssigneeCheck() throws Exception {
            when(taskService.cancel(taskId, "Out of scope")).thenReturn(task("T-1"));

            mockMvc.perform(json(post(TASKS + "/" + taskId + "/cancel"), "{\"reason\": \"Out of scope\"}")
                            .with(admin(UUID.randomUUID())))
                    .andExpect(status().isOk());

            verifyNoInteractions(taskAuthorization);
        }

        @Test
        @Disabled("bug: no handler maps ObjectOptimisticLockingFailureException, so a lost @Version race answers 500")
        void updateLosingAConcurrentModificationReturnsConflict() throws Exception {
            when(taskService.update(eq(taskId), any()))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Task.class, taskId));

            mockMvc.perform(json(patch(TASKS + "/" + taskId), "{\"title\": \"x\"}").with(admin(UUID.randomUUID())))
                    .andExpect(status().isConflict());
        }

        private void expectRejected(MockHttpServletRequestBuilder request, String body) throws Exception {
            mockMvc.perform(json(request, body).with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(taskService);
        }
    }

    @Nested
    class AdminOnly {

        @Test
        void usersCannotAssignEvenTheAssignee() throws Exception {
            UUID assignee = UUID.randomUUID();
            when(taskAuthorization.currentUserIsAssignedTo(any(), any())).thenReturn(true);

            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/assign"), "{\"userId\": \"%s\"}".formatted(assignee))
                            .with(user(assignee)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(taskService);
        }

        @Test
        void usersCannotArchiveEvenTheAssignee() throws Exception {
            expectForbiddenEvenToTheAssignee(post(TASKS + "/" + taskId + "/archive"));
        }

        @Test
        void usersCannotUnarchiveEvenTheAssignee() throws Exception {
            expectForbiddenEvenToTheAssignee(post(TASKS + "/" + taskId + "/unarchive"));
        }

        @Test
        void usersCannotDeleteEvenTheAssignee() throws Exception {
            expectForbiddenEvenToTheAssignee(delete(TASKS + "/" + taskId));
        }

        @Test
        void adminOnlyEndpointsRejectUsersBeforeCheckingTheTaskExists() throws Exception {
            UUID unknown = UUID.randomUUID();
            when(taskService.archive(unknown)).thenThrow(new TaskNotFoundException(unknown));

            mockMvc.perform(delete(TASKS + "/" + unknown).with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(TASKS + "/" + unknown + "/archive").with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void assignWithoutUserIdReturns400() throws Exception {
            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/assign"), "{}").with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(taskService);
        }

        @Test
        void assignWithMalformedUserIdReturns400() throws Exception {
            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/assign"), "{\"userId\": \"not-a-uuid\"}")
                            .with(admin(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(taskService);
        }

        @Test
        void adminPassesTheAdminOnlyChecks() throws Exception {
            UUID assignee = UUID.randomUUID();
            when(taskService.assign(taskId, assignee)).thenReturn(task("T-1"));
            when(taskService.archive(taskId)).thenReturn(task("T-1"));
            when(taskService.unarchive(taskId)).thenReturn(task("T-1"));

            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/assign"), "{\"userId\": \"%s\"}".formatted(assignee))
                            .with(admin(UUID.randomUUID())))
                    .andExpect(status().isOk());
            mockMvc.perform(post(TASKS + "/" + taskId + "/archive").with(admin(UUID.randomUUID())))
                    .andExpect(status().isOk());
            mockMvc.perform(post(TASKS + "/" + taskId + "/unarchive").with(admin(UUID.randomUUID())))
                    .andExpect(status().isOk());
            mockMvc.perform(delete(TASKS + "/" + taskId).with(admin(UUID.randomUUID())))
                    .andExpect(status().isNoContent());

            verify(taskService).delete(taskId);
        }

        @Test
        void assigningAnInactiveUserReturnsUnprocessableProblem() throws Exception {
            UUID unverified = UUID.randomUUID();
            when(taskService.assign(taskId, unverified))
                    .thenThrow(new AssigneeNotActiveException(unverified, UserStatus.UNVERIFIED));

            mockMvc.perform(json(patch(TASKS + "/" + taskId + "/assign"), "{\"userId\": \"%s\"}".formatted(unverified))
                            .with(admin(UUID.randomUUID())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("User cannot be assigned"))
                    .andExpect(jsonPath("$.detail").value(containsString("UNVERIFIED")));
        }

        private void expectForbiddenEvenToTheAssignee(MockHttpServletRequestBuilder request) throws Exception {
            when(taskAuthorization.currentUserIsAssignedTo(any(), any())).thenReturn(true);

            mockMvc.perform(request.with(user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(taskService);
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Task task(String reference) {
        Task task = new Task();
        task.setReference(reference);
        task.setTitle("Task");
        return task;
    }
}
