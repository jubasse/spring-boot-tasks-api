# Cartography

Where things are, for coding agents. Rules and traps live in `AGENTS.md`; this file only says where to look. Paths are
relative to `src/main/java/io/julienmetral/tasks` unless they start with `src/`, `docs/` or a root file name.
`CartographyTest` fails when a package, an annotation or a test helper is missing here.

## Repository

| Path | Holds |
|---|---|
| `AGENTS.md` | conventions, traps, architecture rules (`CLAUDE.md` imports it) |
| `README.md` | user-facing: run, configure, deploy, API usage, webhook receiver contract |
| `docs/problems.md` | RFC 9457 problem types (contract with clients) |
| `docs/openapi.json` | committed OpenAPI document, compared by `OpenApiSpecTests` |
| `docs/spring/` | French guides on the Spring concepts used here, plus `cas-pratiques/` |
| `src/main/resources/application.yaml` | every setting and its default; `application-prod.yaml` for deployments |
| `src/main/resources/db/changelog/changes/` | Liquibase changesets `NNN-name.yaml` |
| `src/main/resources/messages.properties` | reworded details of Spring MVC problems (`problemDetail.<exception>`) |
| `src/main/resources/templates/exports/` | Thymeleaf XHTML of the personal data PDF; fonts in `fonts/` |
| `src/test/resources/config/application.yaml` | test overrides (JWT secret, jobs off, limits off) |
| `postman/` | Postman collection and its upload fixtures |
| `compose.yaml`, `compose.production.yaml` | development services; hardened local run of the image |
| `Dockerfile`, `.dockerignore` | image build with AOT training run; allowlist of build inputs |
| `.github/workflows/ci.yml` | tests, gitleaks, API contract, image scan, publish on tag |

## Packages

Each feature uses the same sub-packages: `controllers`, `dtos`, `entities`, `repositories` (`*Repository` for JPA,
`*Queries` for native SQL), `services`, `exceptions`, `security`, `events`, `mail` (email senders), `messaging`
(RabbitMQ queues and listeners).

| Package | Holds |
|---|---|
| `config` | app-wide technical config: `*Properties` records, storage drivers, cache, clock, OpenAPI customizers, versioning, scheduler lock, required settings |
| `export` | `ExportConfiguration`, `ExportProperties` |
| `export/batch` | Spring Batch jobs (`CsvExportJobs`, `PersonalDataJob`), CSV aggregator, file area, publish step, lease renewal |
| `export/controllers` | `DataExportController` |
| `export/dtos` | export request and response DTOs |
| `export/entities` | `DataExport`, its status, type and filters |
| `export/events` | `DataExportCompleted`, `DataExportFailed` |
| `export/exceptions` | export in progress (409), not found |
| `export/mail` | `DataExportEmailSender` |
| `export/messaging` | `ExportQueues`, `DataExportListener`, `DataExportRequested` |
| `export/personaldata` | `PersonalDataQueries` (native SQL of one account), `PersonalDataPdf` |
| `export/repositories` | `DataExportRepository`, `BatchMetadataQueries` (Batch history purge) |
| `export/services` | `DataExportService`, `DataExportRunner`, recovery and purge jobs |
| `identity/controllers` | `AuthController`, `UserController`, `IdenticonController` |
| `identity/dtos` | auth, user and profile DTOs (`UserProfileResponseDto`) |
| `identity/entities` | `User`, `UserProfile`, `UserRole`, `UserStatus`, the three token entities |
| `identity/events` | `AccountStateChanged` |
| `identity/exceptions` | credentials, tokens, email taken, user not found |
| `identity/mail` | verification, reset and inactivity emails with their events and properties |
| `identity/messaging` | `AvatarQueues`, `AvatarProcessingListener`, `AvatarUploaded` |
| `identity/repositories` | user, profile and token repositories, `AccountState`, `UserRetentionQueries`, `ExpiredTokenQueries` |
| `identity/security` | `SecurityConfiguration`, JWT, `CurrentUser`, `PublicEndpoints`, `OpaqueTokens`, `ActiveUserAuthorizationManager`, status cache |
| `identity/services` | auth, users, tokens, password reset, verification, avatars, identicons, GDPR retention, expired token purge |
| `mail` | `MailService`, `MailMessage`, `MailQueues`, `MailQueueListener` |
| `media/exceptions` | upload problems: empty, too large, unsupported, infected, invalid image, storage or antivirus down |
| `media/health` | `StorageHealthIndicator`, `AntivirusHealthIndicator` |
| `media/model` | `Media`, `MediaUsage` (limits and types per usage), value records |
| `media/repositories` | `MediaRepository`, `MediaCleanupQueries` |
| `media/services` | `MediaService`, `MediaUrls`, `ObjectStorage`/`S3ObjectStorage`, `VirusScanner`/`ClamAvScanner`, type detection, avatar processing, cleanup |
| `messaging/entities` | `OutboxMessage` |
| `messaging/repositories` | `OutboxMessageRepository` |
| `messaging/services` | `Outbox` (enqueue, broadcast), `OutboxRelay`, `OutboxRelayJob`, `OutboxPurgeJob`, outbox and dead-letter metrics |
| `notification/controllers` | notification settings, webhook endpoints |
| `notification/dtos` | settings and webhook DTOs |
| `notification/entities` | `NotificationSettings`, `TaskNotificationType`, webhook endpoint, delivery, event, kind |
| `notification/events` | `TaskNotificationCreated` |
| `notification/exceptions` | webhook limit, URL refused, not signed, not found |
| `notification/mail` | `TaskNotificationSender` |
| `notification/repositories` | settings and webhook repositories, `WebhookDeliveryQueries` |
| `notification/services` | `TaskNotificationPublisher` (shared recipient rules), settings, webhook endpoints |
| `notification/webhook` | delivery: `WebhookClient`, `WebhookDeliveryService`, signer, secrets, URL policy, resilience, Slack, retry and purge jobs |
| `ratelimit/entities` | `RateLimitCounter` |
| `ratelimit/exceptions` | `RateLimitExceededException` (429) |
| `ratelimit/repositories` | `RateLimitQueries` |
| `ratelimit/services` | `RateLimiter`, `RateLimitKeys`, `RateLimitCleanupJob` |
| `realtime` | `RealtimeConfiguration`, `RealtimeProperties` |
| `realtime/controllers` | `NotificationStreamController` (SSE) |
| `realtime/exceptions` | too many streams (429) |
| `realtime/messaging` | broadcasts to every instance: `RealtimeBroadcasts`, `RealtimeListener`, message records |
| `realtime/sse` | `NotificationStreams`, `NotificationStream`, replay buffer |
| `realtime/stomp` | `StompConfiguration`, `StompAuthentication`, `StompSessions`, task rooms |
| `shared/config` | `JpaAuditingConfiguration` |
| `shared/entities` | `AuditableEntity` (timestamps, soft delete), `ScheduledJobLock` |
| `shared/exceptions` | `ApiExceptionHandler`, `ProblemType`, `InvalidValue` |
| `shared/openapi` | `@DocumentedProblems`, `@RateLimited` |
| `shared/security` | role annotations and `@AccessDescription` |
| `task/controllers` | tasks, comments, attachments, history |
| `task/dtos` | task, comment, attachment and event DTOs |
| `task/entities` | `Task`, `TaskComment`, `TaskAttachment`, `TaskEvent`, `TaskReminder` and enums |
| `task/events` | domain events (assigned, cancelled, commented, mentioned, due soon, overdue, `TaskEventRecorded`) |
| `task/exceptions` | task problems (not found, reference taken, assignee not active, invalid mention, too many files) |
| `task/repositories` | task, comment, attachment, event repositories, `TaskReminderQueries` |
| `task/security` | task ownership annotations and their beans |
| `task/services` | `TaskService`, `TaskEventService`, comments, attachments, `CommentMentions`, reminders |

## Find

| Need | Where |
|---|---|
| acting user id | `identity/security/CurrentUser` |
| current time | inject `Clock` (`config/ClockConfiguration`); tests move `TestClock` |
| profile for a response | `UserProfileResponseDto`, `MediaUrls.avatarOf`; load proxies with `identity/services/ProfilesForDisplay` |
| account active check | `identity/security/UserStatusLookup` (cached), `ActiveUserAuthorizationManager` |
| send an email | `mail/MailService.send(MailMessage)` from an event listener |
| publish to RabbitMQ | `messaging/services/Outbox.enqueue` (queue) or `broadcast` (every instance) |
| queue declarations | `*Queues` classes: `MailQueues`, `AvatarQueues`, `WebhookQueues`, `ExportQueues`, `RealtimeConfiguration` |
| trusted message packages | `config/MessagingConfiguration` |
| store or download a file | `media/services/MediaService` (`store`, `storeGenerated`, `downloadUrl`, `delete`) |
| random token, hash only stored | `identity/security/OpaqueTokens` |
| encrypt a secret at rest | `notification/webhook/WebhookSecrets` (AES-256-GCM) |
| limit a public endpoint | `ratelimit/services/RateLimiter`, keys from `RateLimitKeys` |
| outgoing HTTP client | `config/OutboundHttpConfiguration` (address filter, timeouts) |
| native SQL | `*Queries` classes, one per need (`RateLimitQueries`, `TaskReminderQueries`, `UserRetentionQueries`, `ExpiredTokenQueries`, `MediaCleanupQueries`, `WebhookDeliveryQueries`, `PersonalDataQueries`, `BatchMetadataQueries`) |
| scheduled jobs | `*Job` classes; lock names in `config/ScheduledJobLocks` |
| error to problem | `shared/exceptions/ApiExceptionHandler`, typed ones in `ProblemType` |
| OpenAPI rules added to every operation | `config/OperationDocumentation`, `PathDocumentation`, `ProblemDocumentation` |
| API version prefix | `config/ApiVersioningConfiguration`, `PathVersionParser` |
| caches | `config/CacheConfiguration` and `spring.cache.cache-names` |
| required production settings | `deployment.required-properties` in `application-prod.yaml`, checked by `config/RequiredPropertiesCheck` |
| health indicators | `media/health` |

## Annotations of this project

| Annotation | Where | Use |
|---|---|---|
| `@AdminOnly` | `shared/security` | admins only |
| `@AllowedRolesOrSelfOnly` | `shared/security` | roles, or the user named by `#id` |
| `@AllowedRolesOrAssignedToOnly` | `task/security` | roles, or the task's assignee (`#id`) |
| `@AllowedRolesOrWithoutAssigneeOnly` | `task/security` | roles, or a creation without assignee (`#dto`) |
| `@AllowedRolesOrUploaderOnly` | `task/security` | roles, or the attachment's uploader (`#attachmentId`) |
| `@CommentAuthorOnly` | `task/security` | the comment's author (`#commentId`) |
| `@AllowedRolesOrCommentAuthorOnly` | `task/security` | roles, or the comment's author |
| `@AccessDescription` | `shared/security` | sentence of a rule in the API docs; required on every rule above |
| `@DocumentedProblems` | `shared/openapi` | typed problems an operation can answer |
| `@RateLimited` | `shared/openapi` | documents the 429 of an operation that calls `RateLimiter` |
| `@IntegrationTest` | test `support` | full app, containers, MockMvc, Mailpit, `TestClock` |
| `@WebLayerTest` | test `support` | controllers and real security, services mocked, no Docker |
| `@JdbcSliceTest` | test `support` | `JdbcTemplate` and `*Queries`, rolled back |
| `@RepositoryTest` | test `support` | JPA repositories, rolled back |
| `@DeadLetterIntegrationTest` | test `support` | fast listener retries, SMTP mock, storage and antivirus spies |

## Test helpers

In `src/test/java/io/julienmetral/tasks/support` unless noted.

| Helper | Use |
|---|---|
| `TestClock` | the app `Clock` in integration tests: `set`, `advance`; reset after each test |
| `WebCallers` | `user`, `admin`, `withoutUid` callers and `everyAccountIsActive` for `@WebLayerTest` |
| `Mailpit` | read received emails: `latestTextTo`, `latestVerificationTokenFor`, `countTo` |
| `Problems` | MockMvc matchers: `typedProblem`, `untypedProblem`, `validationError`, `invalidParameter` |
| `SqlStatementCounter` | `statementsDuring` counts the SQL of the test thread |
| `Transactions` | `inNewTransaction`: commit work on another connection mid-test |
| `UserProfiles` | `UserProfile` fixtures: `reference`, `active`, `profile` |
| `ProfilePhotos` | `givePhoto`, `givePendingPhoto` without upload or scan |
| `Presigning` | `waitForTheNextSecond` before comparing two presigned URLs |
| `ClearCachesAfterEach` | empties every cache after each test (used by `@IntegrationTest`) |
| `TestcontainersConfiguration` | test package root: RabbitMQ, Mailpit, RustFS, ClamAV containers |
| `PostgresTestcontainersConfiguration` | test package root: the one Postgres definition |
| `TestTasksApplication` | test package root: `./mvnw spring-boot:test-run` |
| `Abstract*Tests` | per-feature integration bases with fixtures (`createUser`, ...): `task/controllers`, `task/services`, `identity/controllers`, `export`, `media/services` |

## Adding something

| Addition | Also touch |
|---|---|
| endpoint | path without `/api/v1`; `@Tag`, `@Operation(summary)`, unique method name, `@ResponseStatus` for 201/202/204, `@DocumentedProblems`, `@RateLimited`, a role annotation; `PublicEndpoints` if public; regenerate `docs/openapi.json` (`-Dtest=OpenApiSpecTests -Dopenapi.update=true`); Postman |
| exception | handler in `ApiExceptionHandler`; if typed: `ProblemType` constant and a section of `docs/problems.md` |
| entity or column | a changeset in `db/changelog/changes/`, constraint names `<table>_<column>UQ`/`FK`, `<table>PK` |
| scheduled job | lock name in `ScheduledJobLocks` (or `UNLOCKED_POLLERS` in `ScheduledJobLocksTest`), `@SchedulerLock`, an `enabled` property; off in tests |
| RabbitMQ message | `Outbox`, a `*Queues` declaration with its dead-letter queue, package trusted in `MessagingConfiguration` |
| setting | `*Properties` record with one `@param` per property, `application.yaml`, `.env.example`, README Configuration; without a safe default: `deployment.required-properties`; required at startup: a throwaway value in the `Dockerfile` training run |
| cache | `spring.cache.cache-names` and `CacheConfiguration` |
| account state change | publish `AccountStateChanged` (cache eviction, broadcasts) |
| task mutation | the matching `TaskEventService` method |
| package, annotation or test helper | a row in this file |

## Guard tests

| Test | Fails when |
|---|---|
| `AccessDescriptionTest` | a security rule lacks `@AccessDescription`, or a `@PreAuthorize` is written on a controller |
| `ScheduledJobLocksTest` | a `@Scheduled` method has no listed lock, or a lock is misconfigured |
| `ProblemTypeTest` | `ProblemType` and `docs/problems.md` disagree |
| `OpenApiSpecTests` | the served document differs from `docs/openapi.json` |
| `OpenApiDocumentTests` | an operation lacks a tag, summary, unique id or its 401/403 |
| `TaskQueryCountTests`, `UserQueryCountTests` | a read runs more SQL for a longer list |
| `CartographyTest` | this file misses a package, an annotation or a test helper |
