# Tasks API

Tasks API is the backend of a team task tracker, exposed as a REST API under `/api/v1`. People sign up, confirm their email address, and then create, assign and follow tasks together:

- **Tasks** have a reference, a status, a priority and a due date. They can be assigned, archived, cancelled and deleted, and every change is recorded in the task's history.
- **Collaboration**: tasks take comments, with attached files and mentions of other users (written `<@user-id>` in the comment), and files can be attached to a task directly.
- **Email notifications** tell the assignee when a task is assigned, cancelled, deleted or commented, tell mentioned people about the mention, and remind the assignee before and after the due date. Each user chooses which of these emails they receive.
- **Accounts**: sign-up, email verification, password reset, and a profile photo. Every user has an image: their photo, or a generated geometric figure when they have none, or when their account is disabled or deleted. Admins can disable, re-enable and delete accounts.
- **Files** are checked before they are stored: the type is detected from the content, the size is limited, and an antivirus scans every upload. Profile photos are cropped to a square and stripped of their metadata, GPS location included.
- **Personal data retention**: a deleted account is erased after 30 days; tasks, comments and history then show a "Deleted user". An account unused for 2 years receives a warning email, and is deleted 30 days later unless its owner logs in.

Only accounts that are enabled and have a verified email address can work on tasks. The endpoints anyone can call (login, sign-up, password reset, resending the verification email) accept a limited number of requests per client address and per account; over the limit, the API answers 429 with a `Retry-After` header giving the seconds to wait.

## Requirements

- JDK 25. You do not need to install Maven: the project ships the Maven wrapper (`./mvnw`).
- Docker with Docker Compose. The development services and the tests run in containers.
- About 3 GB of free memory for the development services, most of it for the antivirus. On a smaller machine, see [Run without the antivirus](#run-without-the-antivirus).

## Run the API locally

1. Create your local configuration from the example:

   ```bash
   cp .env.example .env
   ```

2. Generate two keys and put them in `.env`: one as `JWT_SECRET`, which signs the access tokens, and another as `WEBHOOK_ENCRYPTION_KEY`, which encrypts the webhook secrets. Run this once per key:

   ```bash
   openssl rand -base64 32
   ```

3. Start the API from the project root, since the `.env` path is relative:

   ```bash
   ./mvnw spring-boot:run
   ```

   The first start pulls the container images and starts the services of `compose.yaml` (PostgreSQL, RabbitMQ, the object storage, the antivirus and a mail catcher). The database schema is created on startup.

The API listens on http://localhost:8080, and its health and metrics on http://localhost:8081 (see [Monitor the API](#monitor-the-api)). The development services come with web consoles:

| Service | URL | Sign-in |
|---|---|---|
| Mailpit, every email the API sends | http://localhost:8025 | none |
| RabbitMQ management | http://localhost:15672 | `tasks` / `tasks-dev` |
| RustFS console, the stored files | http://localhost:9001 | `tasks-dev` / `tasks-dev-secret` |

The sign-ins are the defaults of `compose.yaml`; the variables in `.env.example` override them.

### Try it with curl

Create an account:

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.martin@example.com", "password": "correct-horse-42", "displayName": "Alice Martin"}'
```

Open Mailpit, copy the token from the verification link in Alice's email, and confirm the address:

```bash
curl -X POST http://localhost:8080/api/v1/auth/verify-email \
  -H 'Content-Type: application/json' \
  -d '{"token": "<token from the email>"}'
```

Log in. The response holds an `accessToken`, valid 15 minutes, and a `refreshToken` to get a new one from `POST /api/v1/auth/refresh`:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.martin@example.com", "password": "correct-horse-42"}'
```

Create a task with the access token:

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{"reference": "WEB-142", "title": "Fix the login page layout on mobile", "dueAt": "2026-10-15T17:00:00Z"}'
```

### Make an account an admin

No endpoint grants the admin role, so the first admin is set in the database:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "INSERT INTO user_roles (user_id, role) SELECT id, '\''ADMIN'\'' FROM users WHERE email = '\''alice.martin@example.com'\''"'
```

The role is part of the access token, so log in again afterwards.

### Explore the API in the browser

Open http://localhost:8080/swagger-ui.html. Every endpoint is listed with its parameters, responses and errors, and you can call it from the page: sign in with `POST /api/v1/auth/login`, copy the `accessToken`, then paste it in **Authorize**.

Every path starts with the API version, `/api/v1`; a version the API does not support answers 400.

The OpenAPI document behind the page is at http://localhost:8080/v3/api-docs, and a copy is kept in [docs/openapi.json](docs/openapi.json), so a change to the API shows in the diff of its pull request. To generate a client, use that file.

### Explore every endpoint with Postman

`postman/tasks-api.postman_collection.json` covers every endpoint, with test scripts. Import it into Postman, or run it with newman:

1. Run the `0. Setup` folder, which signs up a user, an admin and an unverified user:

   ```bash
   npx newman@6 run postman/tasks-api.postman_collection.json --folder "0. Setup"
   ```

2. Make the admin account (`admin@example.com` by default) an admin, as described above.
3. Run the other folders, passing the id of the unverified user as `unverifiedUserId`.

The collection reads the verification and password reset emails from Mailpit, so run it against the local services.

### Run without the antivirus

Add `ANTIVIRUS_ENABLED=false` to `.env`. Uploads are then stored without being scanned, and the API logs a warning at startup. Use this only on a development machine.

## Receive task notifications by webhook

Besides email, an account can have its task notifications sent to an HTTPS endpoint of its own, such as an automation service or a chat integration. Each notification is a signed JSON `POST`.

### Declare a webhook

```bash
curl -X POST http://localhost:8080/api/v1/users/$USER_ID/webhooks \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url": "https://hooks.example.com/tasks", "events": ["task.assigned", "task.commented"]}'
```

The response carries the webhook's signing secret, `whsec_...`. Store it now: no later response shows it again.

- **URL:** HTTPS on the default port, without a user name or password, and resolving to a public address. A refused URL answers 422 `webhook-url-not-allowed`.
- **Events:** `task.assigned`, `task.unassigned`, `task.cancelled`, `task.deleted`, `task.commented`, `task.mentioned`, `task.due_soon` and `task.overdue`. You never receive an event about your own action, and nothing is sent while your account is disabled or its email is not verified.
- **Limit:** 5 webhooks per account.

### Send them to Slack

Create an [incoming webhook](https://api.slack.com/messaging/webhooks) in your Slack workspace, then declare its URL with `"kind": "SLACK"`:

```bash
curl -X POST http://localhost:8080/api/v1/users/$USER_ID/webhooks \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"kind": "SLACK", "url": "https://hooks.slack.com/services/T0123/B0456/abcdef", "events": ["task.assigned", "task.mentioned"]}'
```

- Each notification arrives as a short message in the webhook's channel, such as "*Alice Martin* assigned you *OPS-142*: Renew the TLS certificate".
- The URL must start with `https://hooks.slack.com/services/`. It works like a password, so responses show it masked; to keep it in a `PUT`, send the masked value back.
- Slack messages are not signed, and there is no secret.
- If Slack reports that the webhook was revoked or its channel deleted or archived, the webhook is disabled.

### Read a notification

```json
{
  "type": "task.assigned",
  "timestamp": "2026-09-28T09:15:02.311Z",
  "data": {
    "task": {"id": "0199a3c4-6f1e-7b52-9d0a-2f6e8c1b4a77", "reference": "OPS-142", "title": "Renew the TLS certificate"},
    "actor": {"id": "0199a3c1-2b7d-7e90-8a41-5c3d9e0f1b26", "displayName": "Alice Martin"}
  }
}
```

`task.cancelled` adds a `reason`, `task.commented` and `task.mentioned` add a `comment` with an `excerpt`, and `task.due_soon` and `task.overdue` add a `dueAt`. For due dates, `actor` is `null`.

### Check the signature

Each request carries three headers, from the [Standard Webhooks](https://www.standardwebhooks.com/) specification: `webhook-id`, `webhook-timestamp` (Unix seconds) and `webhook-signature`. Verify them before trusting the body, with one of the [Standard Webhooks libraries](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries) and your secret:

```java
new Webhook("whsec_...").verify(body, headers);
```

To do it yourself: Base64-decode the secret without its `whsec_` prefix, compute the HMAC-SHA256 of `{webhook-id}.{webhook-timestamp}.{body}`, and compare its Base64 with each `v1,` entry of `webhook-signature`. Refuse a timestamp more than 5 minutes old.

### Answer, and what happens when you cannot

Answer with any 2xx status within 15 seconds. Redirects are not followed.

- **A failure** (another status, no answer in time, no connection) is retried after about 5 seconds, 5 minutes, 30 minutes, 2 hours, 5 hours and 10 hours; a `Retry-After` header you send is honoured. After the last retry, the notification is dropped.
- **A host that keeps failing:** when half the calls to one host fail within a minute, notifications to that host pause for a minute, then one is sent to test it. Paused notifications do not use up their retries.
- **410 Gone** stops everything: the webhook is disabled, and no more notifications are sent to it.
- **Duplicates:** a notification can arrive twice. Its `webhook-id` stays the same on every attempt, so ignore an id you have already processed.

- **A receiver that keeps failing:** when every attempt has failed for 3 days, the webhook is disabled and you get an email. Fix the receiver, then turn the webhook back on.

Each notification and its attempts are listed at `GET /api/v1/users/{id}/webhooks/{webhookId}/deliveries`, newest first, for 30 days. To send one again with a fresh retry schedule, `POST .../deliveries/{deliveryId}/redeliver`; it keeps its `webhook-id`.

### Test a webhook

`POST /api/v1/users/{id}/webhooks/{webhookId}/test` sends a signed `webhook.test` event at once, even to a paused webhook, and answers how your receiver responded:

```json
{"delivered": false, "statusCode": 500, "error": null, "durationMillis": 184}
```

When no answer came back, `statusCode` is absent and `error` says why: `Timeout`, `ConnectionFailed` or `DestinationNotAllowed`. You can send 10 test events per hour.

### Pause, change or rotate

- `PUT /api/v1/users/{id}/webhooks/{webhookId}` replaces the URL and events, and pauses (`"enabled": false`) or resumes the webhook, also after a 410 or an automatic disabling.
- `POST /api/v1/users/{id}/webhooks/{webhookId}/secret` gives a new secret. For the next 24 hours, each request is signed with both the old and the new one, so you can switch without losing any.
- `DELETE /api/v1/users/{id}/webhooks/{webhookId}` removes it with its delivery history.

### Receive them on your machine

In development, set `WEBHOOK_REQUIRE_HTTPS=false` and `OUTBOUND_HTTP_ALLOWED_ADDRESSES=127.0.0.1/32` in `.env`, then declare a URL such as `http://127.0.0.1:9090/hooks`.

## Receive task notifications as they happen

A client that is open, such as a web page, can receive the same notifications as the webhooks while they happen, from a stream of [server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html):

```bash
curl -N http://localhost:8080/api/v1/notifications/stream -H "Authorization: Bearer $TOKEN"
```

```text
retry:4211

id:01a0ebb0-1916-73c6-a899-2801bd717d66
event:task.assigned
data:{"type":"task.assigned","timestamp":"2026-09-29T05:43:01.651Z","data":{"task":{...},"actor":{...}}}
```

- **Events:** every notification of the account, whatever its email settings, with the webhook payload as data (see Read a notification). You never receive your own actions, and the stream needs an active account (verified email, not disabled); disabling the account closes it.
- **Reconnecting:** the stream ends when the access token expires, after 15 minutes at most. Reconnect with a fresh token and the `Last-Event-ID` header set to the last `id` received: the events missed meanwhile come first. If they are no longer kept (5 minutes), a `resync` event says to reload what you show. An event may come twice: ignore an `id` you already have.
- **Browsers:** `EventSource` cannot send the `Authorization` header. Use a client built on `fetch`, such as the `eventsource` package with its `fetch` option, which sends the header and `Last-Event-ID`.
- **Limit:** 5 open streams per account on each instance; one more answers 429.
- **Behind a proxy:** turn response buffering off for this path (the API sends `X-Accel-Buffering: no` for nginx), and keep read timeouts above 20 seconds: an idle stream sends a comment line every 20 seconds.

## Follow a task live

A page that shows a task can follow its changes over a WebSocket, with the [STOMP](https://stomp.github.io/) protocol: every change made through the API, by anyone, comes as a message in the task's room.

1. Open a WebSocket to `ws://localhost:8080/ws` (`wss://` in production), with the `v12.stomp` subprotocol.
2. Send a `CONNECT` frame with an `Authorization: Bearer <access token>` header: browsers cannot add headers to the WebSocket request itself.
3. Subscribe to `/topic/tasks/<task id>`.

With [@stomp/stompjs](https://github.com/stomp-js/stompjs):

```js
const client = new Client({
  brokerURL: "wss://tasks.example.com/ws",
  beforeConnect: async () => { client.connectHeaders = { Authorization: `Bearer ${await freshAccessToken()}` }; },
  onConnect: () => client.subscribe(`/topic/tasks/${taskId}`, (message) => {
    const event = JSON.parse(message.body);   // {"taskId", "eventId", "type", "actorId", "occurredAt"}
    reloadTask(taskId);
  }),
});
client.activate();
```

- **Messages:** `type` is the history event (`UPDATED`, `STATUS_CHANGED`, `COMMENT_ADDED`...), or `DELETED` when the task is deleted. A message does not carry the new state: read the task again, and its history (`GET /api/v1/tasks/{id}/events`) for the details. An `event-id` header identifies the message: ignore one you already have.
- **Access:** an active account, as for the task endpoints. A refused `CONNECT` or `SUBSCRIBE` answers an `ERROR` frame with the reason, then the connection closes.
- **Sessions:** the connection closes when the access token expires, or as soon as the account is disabled or deleted. `beforeConnect` above reconnects with a fresh token. Changes made while disconnected are not replayed: reload the task after reconnecting.
- **Other origins:** a page served from another origin needs it in `REALTIME_ALLOWED_ORIGINS` (patterns such as `https://*.example.com`); by default only the API's own origin may connect.
- **Behind a proxy:** forward the WebSocket upgrade headers on `/ws`, and keep idle timeouts above 10 seconds: both sides send heartbeats every 10 seconds.

## Export data

Tasks and, for an admin, users can be exported as CSV files, and every account can export its own personal data. An export runs in the background: the request returns at once, and an email tells you when the file is ready.

```bash
curl -i -X POST http://localhost:8080/api/v1/exports/tasks \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status": "IN_PROGRESS", "archived": false}'
```

The answer is `202 Accepted`, with the export in the body and its URL in the `Location` header. Follow it until its `status` is `COMPLETED`:

```bash
curl http://localhost:8080/api/v1/exports/$EXPORT_ID -H "Authorization: Bearer $TOKEN"
```

- **Download:** a completed export carries a `downloadUrl`, valid a few minutes; ask for the export again to get a new one. The file stays available 7 days, then its `status` becomes `EXPIRED`.
- **Kinds:** `POST /api/v1/exports/tasks` takes the filters of the task list (`status`, `assigneeId`, `archived`), for any active account. `POST /api/v1/exports/users` lists every account with its email and roles, for admins only. `POST /api/v1/exports/my-data` exports the caller's personal data (see below).
- **One at a time:** while an export is queued or running, asking for another of the same kind answers 409 `export-in-progress`.
- **Your exports only:** `GET /api/v1/exports` lists yours, and `DELETE /api/v1/exports/{id}` deletes one with its file. Another account's export answers 404.
- **Files:** UTF-8 with a byte order mark, so that Excel reads accents; comma-separated, every value quoted, dates in UTC (ISO 8601). A text that a spreadsheet would run as a formula starts with an apostrophe.

### Export your personal data

`POST /api/v1/exports/my-data` produces a ZIP archive of everything the API holds about your account, as the GDPR's rights of access and portability (articles 15 and 20) ask:

| File | Content |
|---|---|
| `my-data.json` | Everything, for software: your account, your sessions (dates only), email notification settings, webhooks and their recent deliveries, the tasks you created or are assigned to (deleted ones included, with their deletion date), your comments and the mentions of you, the reminders sent to you, the files you attached, what you did on tasks, and your exports. `version` identifies the format |
| `my-data.pdf` | The same, for a person to read; each section shows its first 1000 rows |
| `profile-photo.jpg` (or `.png`, `.webp`) | Your profile photo, when you have one |

Other people appear by display name only, never by email. Secrets never appear: no password, no token, no webhook signing secret, and a Slack webhook URL is masked.

## Commands

| Command | What it does |
|---|---|
| `./mvnw spring-boot:run` | Runs the API with the services of `compose.yaml` |
| `docker compose up -d` | Starts the development services yourself; needed once after `compose.yaml` gains a service (see [Troubleshooting](#troubleshooting)) |
| `./mvnw spring-boot:test-run` | Runs the API against throwaway containers, with an empty database |
| `./mvnw compile` | Builds the project |
| `./mvnw test` | Runs all the unit and integration tests, and writes a coverage report to `target/site/jacoco/index.html` |
| `./mvnw test -Dtest=AuthControllerTests` | Runs one test class (`-Dtest=Class#method` for one test) |
| `./mvnw test -Dtest=OpenApiSpecTests -Dopenapi.update=true` | Regenerates `docs/openapi.json` after a change to the API |
| `./mvnw verify` | Runs what the CI runs: the tests and the coverage report |
| `docker build -t tasks-api .` | Builds the production image (see [Build the image](#build-the-image)) |
| `docker compose -p tasks-prod -f compose.yaml -f compose.production.yaml up -d --build` | Runs the production image locally (see [Run the production image locally](#run-the-production-image-locally)) |
| `gitleaks git . --redact` | Scans the Git history for secrets, as the CI does |

The database migrations are Liquibase changesets in `src/main/resources/db/changelog/changes/`. [CLAUDE.md](CLAUDE.md) explains how to generate a new one from the entities with `./mvnw liquibase:diff`.

## Configuration

The API reads its configuration from `src/main/resources/application.yaml`, which imports `.env`. The defaults below suit local development; a deployed API runs the `prod` profile instead (see [Deploy the API](#deploy-the-api)). The variables you are most likely to change:

| Variable | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | none, required | Base64 key of at least 32 bytes that signs the access tokens |
| `WEBHOOK_ENCRYPTION_KEY` | none, required | Base64 key of at least 32 bytes that encrypts the webhook signing secrets in the database. Changing it makes the secrets of existing webhooks unreadable |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | set in `.env.example` | Database of the local PostgreSQL service |
| `MAIL_FROM` | `no-reply@tasks.local` | Sender address of every email |
| `EMAIL_VERIFICATION_URL`, `PASSWORD_RESET_URL` | `http://localhost:3000/...` | Front-end pages that the emailed links open, with `?token=...` |
| `EXPORT_DOWNLOAD_URL` | `http://localhost:3000/exports` | Front-end page that the export ready email opens, with `?id=...` |
| `STORAGE_DRIVER` | `rustfs` | `rustfs` for the local service, `aws-s3` for Amazon S3 (credentials from the standard AWS variables or an IAM role) |
| `ANTIVIRUS_ENABLED` | `true` | `false` stores uploads without scanning them |
| `RATE_LIMIT_ENABLED` | `true` | `false` turns off the request limits on the public endpoints |
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` behind a reverse proxy, so that limits apply to the client's address from `X-Forwarded-For` rather than the proxy's. Keep `none` without a proxy, or clients could send a fake address |
| `MEDIA_CLEANUP_RETENTION` | `30d` | How long the files of deleted tasks and accounts are kept |
| `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` | provided by Docker Compose | RabbitMQ connection outside local development |
| `MANAGEMENT_PORT` | `8081` | Port of the health and metrics endpoints |
| `WEBHOOK_REQUIRE_HTTPS` | `true` | `false` accepts plain HTTP webhook URLs on any port, for a receiver on your machine. Use it only in development |
| `OUTBOUND_HTTP_ALLOWED_ADDRESSES` | empty | Private address ranges, in CIDR notation, that webhooks may reach besides public addresses, such as `127.0.0.1/32` for a receiver on your machine. Keep it empty in production |
| `REALTIME_ALLOWED_ORIGINS` | empty | Origins of the pages that may open the task rooms' WebSocket, comma-separated patterns such as `https://*.example.com`. Empty allows the API's own origin only |
| `API_DOCS_ENABLED`, `SWAGGER_UI_ENABLED` | `true`, and `false` under the `prod` profile | `false` stops serving the OpenAPI document and Swagger UI |
| `IDENTITY_STATUS_CACHE_TTL` | `30s` | How long an account's status is reused before it is read again, from 1 second to 1 minute. With several instances, the others forget it as soon as RabbitMQ relays the change; while RabbitMQ is down, it is how long an account disabled on one instance can keep working through the others |

`.env.example` lists the other options, and `application.yaml` holds the fixed settings, such as the upload size limits and the schedules of the background jobs.

## Deploy the API

Every deployed environment, staging and production alike, runs the `prod` profile: set `SPRING_PROFILES_ACTIVE=prod` in its environment. Environments differ only by their variables.

Under the `prod` profile, the API:
- refuses to start while a required variable is missing or empty, and names every missing one in a single error;
- writes its logs to standard output as one JSON object per line, in Elastic Common Schema (ECS) format, with `DEPLOYMENT_ENVIRONMENT` (default `production`) as `service.environment`;
- serves neither Swagger UI nor the OpenAPI document; `API_DOCS_ENABLED=true` and `SWAGGER_UI_ENABLED=true` turn them back on, on a staging environment for example;
- stores files in Amazon S3 and expects the bucket to exist;
- sends email through SMTP on port 587, with authentication and STARTTLS required;
- gives requests in progress 20 seconds to finish when it stops.

Required variables:

| Variable | Meaning | Example |
|---|---|---|
| `JWT_SECRET` | Base64 key of at least 32 bytes that signs the access tokens: `openssl rand -base64 32` | |
| `WEBHOOK_ENCRYPTION_KEY` | Base64 key of at least 32 bytes that encrypts the webhook signing secrets, generated the same way. Keep it: a new key makes the secrets of existing webhooks unreadable | |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL 18 database | `jdbc:postgresql://db.internal:5432/tasks` |
| `SPRING_RABBITMQ_HOST` | RabbitMQ host. Set `SPRING_RABBITMQ_USERNAME` and `SPRING_RABBITMQ_PASSWORD` too: the default account, `guest`, only connects from the broker's own machine | `rabbitmq.internal` |
| `MAIL_HOST` | SMTP server. `SPRING_MAIL_USERNAME` and `SPRING_MAIL_PASSWORD` hold its credentials | `smtp.example.com` |
| `MAIL_FROM` | Sender address of every email | `no-reply@example.com` |
| `EMAIL_VERIFICATION_URL`, `PASSWORD_RESET_URL` | Front-end pages that the emailed links open | `https://app.example.com/verify-email` |
| `EXPORT_DOWNLOAD_URL` | Front-end page that the export ready email opens | `https://app.example.com/exports` |
| `S3_BUCKET` | Bucket of the uploaded files. `AWS_REGION` (default `eu-west-3`) and the standard AWS credentials, variables or IAM role, give access to it | `tasks-media` |
| `CLAMAV_HOST` | Host of the ClamAV daemon, on `CLAMAV_PORT` (default `3310`) | `clamav.internal` |

Other variables, when the defaults do not fit:
- `MAIL_PORT`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`: `587`, `true` and `true` by default; set the last two to `false` for a relay that needs neither.
- `STORAGE_DRIVER=rustfs`, with `S3_ENDPOINT`, `S3_ACCESS_KEY` and `S3_SECRET_KEY`, for an S3-compatible server other than Amazon S3.
- The variables of [Configuration](#configuration) keep their meaning, `FORWARD_HEADERS_STRATEGY` behind a reverse proxy in particular.

> Warning: set `SPRING_PROFILES_ACTIVE` only in the environment of a deployment. Exported in your shell or your IDE, it also applies to `./mvnw test` and `./mvnw spring-boot:run`, which then stop and ask for the production variables.

### Build the image

```bash
docker build -t tasks-api .
```

The build compiles the API inside Docker, so it needs neither Java nor Maven on your machine. At the end, it starts the API once, without reaching any service, to record which classes it loads. The container then starts about a third faster.

The image:
- listens on 8080 for the API and on 8081 for health checks and metrics (see [Monitor the API](#monitor-the-api));
- runs as a non-root user and writes only to `/tmp`, where uploads wait while they are checked, so it works with a read-only file system;
- runs the `prod` profile and takes the variables of the tables above.

Give the container at least 1 GB of memory: the JVM takes up to 75% of the container's limit for its heap. The JVM options live in `JAVA_TOOL_OPTIONS` (`-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`). To change one, set the whole variable again with the others kept.

Note: the first lines of output are plain text from the JVM, such as `Picked up JAVA_TOOL_OPTIONS`; the JSON log lines follow.

### Use a released image

Every release is published to GitHub Container Registry, tagged with its version (`0.4.0`), its minor version (`0.4`) and `latest`:

```bash
docker pull ghcr.io/jubasse/spring-boot-tasks-api:0.4.0
```

Each image carries a signed record of the workflow run that built it. Check it with the GitHub CLI before you deploy:

```bash
gh attestation verify oci://ghcr.io/jubasse/spring-boot-tasks-api:0.4.0 -R jubasse/spring-boot-tasks-api
```

### Run the production image locally

`compose.production.yaml` runs the image as a production platform would: `prod` profile, read-only file system, no Linux capabilities, 1 GB of memory and 2 CPUs. It starts its own copy of the services of `compose.yaml`, with its own data, next to your development services. It reads `JWT_SECRET`, `WEBHOOK_ENCRYPTION_KEY` and the other values from your `.env`.

```bash
docker compose -p tasks-prod -f compose.yaml -f compose.production.yaml up -d --build
```

| URL | What you get |
|---|---|
| http://localhost:18080 | The API, with an empty database |
| http://localhost:18081/actuator/health | Health of the API and of each service |
| http://localhost:18025 | Mailpit, with the emails the API sent |

Swagger UI is off, as in production. To make an account an admin, run the command of [Make an account an admin](#make-an-account-an-admin) with `-p tasks-prod` after `docker compose`.

> Warning: pass `-p tasks-prod` to every command on this stack, `down` included. Without it, the command acts on your development services, and `down -v` deletes their data.

To stop the stack and delete its data:

```bash
docker compose -p tasks-prod -f compose.yaml -f compose.production.yaml down -v
```

## Monitor the API

Two probes are served on the API port, 8080, without authentication or details, for the orchestrator or the load balancer:
- `/livez` fails when the process should be restarted;
- `/readyz` fails when the API should not receive traffic.

Point your probes at these rather than at the port 8081 ones: they also fail when the API port stops answering.

Health details and metrics are served on a separate port, 8081 by default, without authentication.

> Warning: make port 8081 reachable only from your monitoring systems, never from the internet.

| URL | What you get |
|---|---|
| http://localhost:8081/actuator/health | Overall status and each dependency: database, RabbitMQ, mail server, object storage, antivirus, disk space |
| http://localhost:8081/actuator/health/liveness | Whether the process should be restarted |
| http://localhost:8081/actuator/health/readiness | Whether the API can take traffic, which only needs the database. RabbitMQ, the object storage or the antivirus being down delays emails or blocks files, and shows in the overall health, but does not take the API out of traffic |
| http://localhost:8081/actuator/prometheus | Metrics in Prometheus format |
| http://localhost:8081/actuator/info | Deployed version |
| http://localhost:8081/actuator/sbom/application | Libraries in the deployed jar and their versions, in CycloneDX format, to check a vulnerability announcement against |

Metrics worth alerting on:

| Metric | Alert when |
|---|---|
| `outbox_messages_pending`, `outbox_messages_oldest_pending_age_seconds` | They keep growing: emails and profile photos are waiting for RabbitMQ |
| `rabbitmq_dead_letter_messages{queue=...}` | Above 0: a message failed all its retries |
| `outbox_publish_failures_total` | It increases steadily |
| `cache_gets_total{cache="userStatus",result=...}` | The share of `hit` falls: every task request reads the account from the database again |
| `tasks_scheduled_execution_seconds_count{outcome="FAILURE"}` | A background job failed |
| `shedlock_lock_acquired_total{lock_name=...}` | It did not increase for longer than the job's period (25 hours for a daily job): no instance ran the job, for example because of a stuck lock |
| `webhook_deliveries_total{outcome="failed"}` | It increases steadily: webhook notifications are being dropped after all their retries |
| `webhook_circuit_breakers_open` | Above 0 for long: some webhook receivers are down, and notifications to them wait |

## Architecture

The code lives under `src/main/java/io/julienmetral/tasks`, organized by feature. Each feature holds its controllers, services, repositories, entities and DTOs:

| Package | Responsibility |
|---|---|
| `identity` | Accounts, login, access and refresh tokens, email verification, password reset, profile photos, personal data retention |
| `task` | Tasks, their history, attachments, comments and due-date reminders |
| `notification` | Task emails, webhooks and each user's notification settings |
| `realtime` | Events relayed to every instance, the notification streams and the live task rooms |
| `export` | CSV and personal data exports, produced by Spring Batch jobs in the background |
| `media` | Stored files: type and size checks, antivirus, object storage, download links, cleanup |
| `mail` | Sending emails, used by every feature |
| `messaging` | Outbox that saves messages for RabbitMQ with the change that triggers them, and publishes them |
| `config` | Technical configuration: storage drivers, antivirus, messaging, scheduling |
| `shared` | Base entity, error responses, reusable authorization annotations |

### Requests

Controllers validate the request and delegate to a service, which owns the database transaction and returns entities; the controller turns them into response DTOs. Errors come back as `application/problem+json` responses (RFC 9457); [docs/problems.md](docs/problems.md) lists them and what a client should do about each. Authentication is stateless: every request carries a signed JWT, and authorization rules are declared on the controller methods.

### Data

PostgreSQL holds all the data, and Liquibase migrations create and evolve the schema. Accounts and tasks are soft-deleted: a deleted row stays in the table, hidden from the API, so the history of other tasks and users keeps pointing to it.

Files live in S3-compatible object storage, never in the database. Downloads do not go through the API: responses carry short-lived signed links to the storage.

### Background work

Work that must not slow down a request, or must survive a failure, goes through RabbitMQ. The message is first saved in the database with the change that triggers it, then published once that change is committed. If RabbitMQ is unreachable, the message waits in the database and is published when the broker is back, so it is delayed but not lost.

| Queue | Consumer |
|---|---|
| `mail.send` | Sends the email over SMTP |
| `avatar.process` | Crops and re-encodes an uploaded profile photo |
| `webhook.deliver` | Sends a task notification to a webhook endpoint (its retries are scheduled in the database) |
| `export.run` | Runs the Spring Batch job of an export, one at a time per instance |

A failed message is retried with a growing delay, then moved to the queue's `.dead-letter` queue, where you can inspect it from the RabbitMQ console.

Real-time events go through the same outbox to the `tasks.realtime` exchange, which copies each of them to a queue of every running instance. That queue belongs to its instance and disappears with it, so events are not kept for an instance that is down; clients of the notification stream catch up when they reconnect.

Scheduled jobs run inside the API. When several instances are deployed, a job that must run once per schedule first takes its row in the `scheduler_locks` table (ShedLock), and the other instances skip that run. The two pollers run on every instance and share the work instead:

| Job | Default schedule | Runs on | What it does |
|---|---|---|---|
| Due-date reminders | every 15 minutes | one instance | Emails assignees about tasks due within 24 hours or just overdue |
| Media cleanup | daily at 03:30 | one instance | Deletes the files of tasks and accounts deleted more than 30 days ago, and orphan files |
| Webhook deliveries purge | daily at 03:45 | one instance | Deletes delivery records older than 30 days |
| Personal data retention | daily at 04:00 | one instance | Anonymizes deleted accounts and handles inactive ones |
| Export purge | daily at 04:30 | one instance | Deletes the files of exports older than 7 days, then expired exports and Spring Batch history older than 30 days |
| Export recovery | every 5 minutes | one instance | Queues again the exports of an instance that stopped while running them |
| Outbox purge | hourly | one instance | Deletes messages published more than 7 days ago |
| Rate limit purge | hourly, at 20 minutes past | one instance | Deletes expired request counters |
| Outbox poller | every 5 seconds | every instance | Publishes the messages RabbitMQ could not take right after their commit |
| Webhook retries | every 30 seconds | every instance | Queues the webhook deliveries due for another attempt |

A lock is released when its job ends, but held at least 30 seconds to 5 minutes, so an instance whose clock is slightly late does not run the job again. If an instance crashes during a job, its lock expires after the job's maximum duration (from 4 minutes to 2 hours). To release a stuck lock earlier, set its `lock_until` to the current time; never delete the row, or the instances that already know it skip the job until they restart:

```sql
UPDATE scheduler_locks SET lock_until = timezone('utc', now()) WHERE name = 'media-cleanup';
```

## Technologies

- **Language and framework**: Java 25, Spring Boot 4.1 (Spring Web MVC, Spring Security as an OAuth2 resource server, Spring Data JPA with Hibernate 7, Spring AMQP, Spring Mail, Bean Validation, Actuator), Jackson 3, Lombok, springdoc-openapi.
- **Data**: PostgreSQL 18, Liquibase.
- **Messaging**: RabbitMQ 4.2.
- **Files**: S3-compatible storage through the AWS SDK for Java 2 (RustFS locally), ClamAV for the antivirus, Apache Tika for type detection, TwelveMonkeys ImageIO and metadata-extractor for profile photos.
- **Security**: HS256 JWT access tokens, rotating opaque refresh tokens, Argon2id password hashing (Bouncy Castle).
- **Tests**: JUnit 5, Mockito, AssertJ, Testcontainers (PostgreSQL, RabbitMQ, Mailpit, RustFS, ClamAV), JaCoCo.
- **Tooling**: Maven wrapper, Docker Compose, GitHub Actions, gitleaks, Postman and newman.

## Tests

Unit tests (`*Test`) and web tests (`*WebMvcTests`, the controllers and security with mocked services) run without Docker. SQL and repository tests start PostgreSQL in a container, and integration tests (the other `*Tests`) start the API against every service in containers, so `./mvnw test` needs Docker running.

A full run takes a few minutes and several GB of memory. Do not run two full runs at the same time on one machine.

### Keep the test containers between runs

Add this line to `~/.testcontainers.properties` (create the file if needed):

```properties
testcontainers.reuse.enable=true
```

PostgreSQL, Mailpit, RustFS and ClamAV then start once, serve every test class, and stay up after the run, so the next run skips their startup and a full run keeps one copy of each instead of one per group of tests. RabbitMQ still starts for each group of tests. The CI turns reuse on as well.

The test database keeps its data from one run to the next. Remove the containers when you change a migration you already ran, when you switch to a branch with different migrations, or when you want `./mvnw spring-boot:test-run` to start on an empty database (it uses the same containers):

```bash
docker rm -f $(docker ps -aq --filter label=io.julienmetral.tasks.test-container)
```

The label matches only this project's test containers.

## Troubleshooting

**The API stops at startup with an error about the JWT secret or `WEBHOOK_ENCRYPTION_KEY`.** The key is missing from `.env` or too short. Generate one with `openssl rand -base64 32`, and start the API from the project root so that `.env` is found.

**The API stops at startup with "Required settings without a value".** It runs the `prod` profile and the settings named in the error have no value. Set their variables (see [Deploy the API](#deploy-the-api)). If this happens on your machine, `SPRING_PROFILES_ACTIVE=prod` is exported in your shell or IDE: remove it.

**The API cannot reach RabbitMQ, ClamAV or the object storage after you pull new changes.** When some services of `compose.yaml` already run, the API does not start the ones added since. Run `docker compose up -d` once.

**Tests fail at startup with a Liquibase checksum error.** The reused test database still holds an older version of a migration you changed. Remove the test containers (see [Keep the test containers between runs](#keep-the-test-containers-between-runs)) and run again. If the command finds no container although tests ran, your `docker` command talks to another Docker daemon than the tests do, for example Docker Desktop next to the native engine: add `--context default`, or the context `docker context ls` lists for the engine the tests use.

**An upload fails with 503 and "Antivirus unavailable".** The antivirus loads its signatures for a minute or two after it starts, and uploads are refused rather than stored unscanned until then. Wait and retry, or see [Run without the antivirus](#run-without-the-antivirus).

**Login, sign-up or password reset answers 429.** Too many requests came from your address, or for that email, within the current window. Wait for the number of seconds in the `Retry-After` header. The limits are under `rate-limit` in `application.yaml`.

**Task endpoints answer 403 for an account that can log in.** The account's email is not verified yet, or an admin disabled it. Only enabled, verified accounts can work on tasks.

**A profile photo does not change after the upload.** The upload answers 202 and the photo is processed in the background: `avatarPending` stays `true` in the account until it is done. If it stays pending, check the `avatar.process.dead-letter` queue in the RabbitMQ console.

**An email never arrives.** Emails leave shortly after the request, in the background. Check Mailpit locally, then:
- the `outbox_messages` table: a row with no `published_at` is waiting for RabbitMQ, and `last_error` says why;
- the `mail.send.dead-letter` queue: a message lands there when the mail server kept failing.

## Contributing

- Branches follow Git flow: `features/<name>` is merged into `develop` through a pull request, and releases go through `release/<version>` to `main`.
- Commits follow Conventional Commits (`feat`, `fix`, `test`, `docs`, ...), and production code and its tests go in separate commits.
- [CLAUDE.md](CLAUDE.md) holds the detailed conventions: architecture rules, the traps to avoid, how to write comments, and how to test.
