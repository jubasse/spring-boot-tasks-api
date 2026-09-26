# Tasks API

Tasks API is the backend of a team task tracker, exposed as a REST API under `/api/v1`. People sign up, confirm their email address, and then create, assign and follow tasks together:

- **Tasks** have a reference, a status, a priority and a due date. They can be assigned, archived, cancelled and deleted, and every change is recorded in the task's history.
- **Collaboration**: tasks take comments, with attached files and mentions of other users (written `<@user-id>` in the comment), and files can be attached to a task directly.
- **Email notifications** tell the assignee when a task is assigned, cancelled, deleted or commented, tell mentioned people about the mention, and remind the assignee before and after the due date. Each user chooses which of these emails they receive.
- **Accounts**: sign-up, email verification, password reset, and a profile photo. Admins can disable, re-enable and delete accounts.
- **Files** are checked before they are stored: the type is detected from the content, the size is limited, and an antivirus scans every upload. Profile photos are cropped to a square and stripped of their metadata, GPS location included.
- **Personal data retention**: a deleted account's personal data is erased after 30 days. An account unused for 2 years receives a warning email, and is deleted 30 days later unless its owner logs in.

Only accounts that are enabled and have a verified email address can work on tasks.

## Requirements

- JDK 25. You do not need to install Maven: the project ships the Maven wrapper (`./mvnw`).
- Docker with Docker Compose. The development services and the tests run in containers.
- About 3 GB of free memory for the development services, most of it for the antivirus. On a smaller machine, see [Run without the antivirus](#run-without-the-antivirus).

## Run the API locally

1. Create your local configuration from the example:

   ```bash
   cp .env.example .env
   ```

2. Generate a signing key for the access tokens and put it in `.env` as `JWT_SECRET`:

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

## Commands

| Command | What it does |
|---|---|
| `./mvnw spring-boot:run` | Runs the API with the services of `compose.yaml` |
| `docker compose up -d` | Starts the development services yourself; needed once after `compose.yaml` gains a service (see [Troubleshooting](#troubleshooting)) |
| `./mvnw spring-boot:test-run` | Runs the API against throwaway containers, with an empty database |
| `./mvnw compile` | Builds the project |
| `./mvnw test` | Runs all the unit and integration tests, and writes a coverage report to `target/site/jacoco/index.html` |
| `./mvnw test -Dtest=AuthControllerTests` | Runs one test class (`-Dtest=Class#method` for one test) |
| `./mvnw verify` | Runs what the CI runs: the tests and the coverage report |
| `gitleaks git . --redact` | Scans the Git history for secrets, as the CI does |

The database migrations are Liquibase changesets in `src/main/resources/db/changelog/changes/`. [CLAUDE.md](CLAUDE.md) explains how to generate a new one from the entities with `./mvnw liquibase:diff`.

## Configuration

The API reads its configuration from `src/main/resources/application.yaml`, which imports `.env`. The variables you are most likely to change:

| Variable | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | none, required | Base64 key of at least 32 bytes that signs the access tokens |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | set in `.env.example` | Database of the local PostgreSQL service |
| `MAIL_FROM` | `no-reply@tasks.local` | Sender address of every email |
| `EMAIL_VERIFICATION_URL`, `PASSWORD_RESET_URL` | `http://localhost:3000/...` | Front-end pages that the emailed links open, with `?token=...` |
| `STORAGE_DRIVER` | `rustfs` | `rustfs` for the local service, `aws-s3` for Amazon S3 (credentials from the standard AWS variables or an IAM role) |
| `ANTIVIRUS_ENABLED` | `true` | `false` stores uploads without scanning them |
| `MEDIA_CLEANUP_RETENTION` | `30d` | How long the files of deleted tasks and accounts are kept |
| `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` | provided by Docker Compose | RabbitMQ connection outside local development |
| `MANAGEMENT_PORT` | `8081` | Port of the health and metrics endpoints |

`.env.example` lists the other options, and `application.yaml` holds the fixed settings, such as the upload size limits and the schedules of the background jobs.

## Monitor the API

Health checks and metrics are served on a separate port, 8081 by default, without authentication.

> Warning: make port 8081 reachable only from your monitoring systems, never from the internet.

| URL | What you get |
|---|---|
| http://localhost:8081/actuator/health | Overall status and each dependency: database, RabbitMQ, mail server, object storage, antivirus, disk space |
| http://localhost:8081/actuator/health/liveness | Whether the process should be restarted |
| http://localhost:8081/actuator/health/readiness | Whether the API can take traffic: database, RabbitMQ and object storage are up. An unreachable antivirus only blocks uploads, so it does not make the API unready |
| http://localhost:8081/actuator/prometheus | Metrics in Prometheus format |
| http://localhost:8081/actuator/info | Deployed version |

Metrics worth alerting on:

| Metric | Alert when |
|---|---|
| `outbox_messages_pending`, `outbox_messages_oldest_pending_age_seconds` | They keep growing: emails and profile photos are waiting for RabbitMQ |
| `rabbitmq_dead_letter_messages{queue=...}` | Above 0: a message failed all its retries |
| `outbox_publish_failures_total` | It increases steadily |
| `tasks_scheduled_execution_seconds_count{outcome="FAILURE"}` | A background job failed |

## Architecture

The code lives under `src/main/java/io/julienmetral/tasks`, organized by feature. Each feature holds its controllers, services, repositories, entities and DTOs:

| Package | Responsibility |
|---|---|
| `identity` | Accounts, login, access and refresh tokens, email verification, password reset, profile photos, personal data retention |
| `task` | Tasks, their history, attachments, comments and due-date reminders |
| `notification` | Task emails and each user's notification settings |
| `media` | Stored files: type and size checks, antivirus, object storage, download links, cleanup |
| `mail` | Sending emails, used by every feature |
| `messaging` | Outbox that saves messages for RabbitMQ with the change that triggers them, and publishes them |
| `config` | Technical configuration: storage drivers, antivirus, messaging, scheduling |
| `shared` | Base entity, error responses, reusable authorization annotations |

### Requests

Controllers validate the request and delegate to a service, which owns the database transaction and returns entities; the controller turns them into response DTOs. Errors come back as `application/problem+json` responses (RFC 9457). Authentication is stateless: every request carries a signed JWT, and authorization rules are declared on the controller methods.

### Data

PostgreSQL holds all the data, and Liquibase migrations create and evolve the schema. Accounts and tasks are soft-deleted: a deleted row stays in the table, hidden from the API, so the history of other tasks and users keeps pointing to it.

Files live in S3-compatible object storage, never in the database. Downloads do not go through the API: responses carry short-lived signed links to the storage.

### Background work

Work that must not slow down a request, or must survive a failure, goes through RabbitMQ. The message is first saved in the database with the change that triggers it, then published once that change is committed. If RabbitMQ is unreachable, the message waits in the database and is published when the broker is back, so it is delayed but not lost.

| Queue | Consumer |
|---|---|
| `mail.send` | Sends the email over SMTP |
| `avatar.process` | Crops and re-encodes an uploaded profile photo |

A failed message is retried with a growing delay, then moved to the queue's `.dead-letter` queue, where you can inspect it from the RabbitMQ console.

Scheduled jobs run inside the API. Each job takes a PostgreSQL lock first, so only one instance runs it when several are deployed:

| Job | Default schedule | What it does |
|---|---|---|
| Due-date reminders | every 15 minutes | Emails assignees about tasks due within 24 hours or just overdue |
| Media cleanup | daily at 03:30 | Deletes the files of tasks and accounts deleted more than 30 days ago, and orphan files |
| Personal data retention | daily at 04:00 | Anonymizes deleted accounts and handles inactive ones |

## Technologies

- **Language and framework**: Java 25, Spring Boot 4.1 (Spring Web MVC, Spring Security as an OAuth2 resource server, Spring Data JPA with Hibernate 7, Spring AMQP, Spring Mail, Bean Validation, Actuator), Jackson 3, Lombok, springdoc-openapi.
- **Data**: PostgreSQL 18, Liquibase.
- **Messaging**: RabbitMQ 4.2.
- **Files**: S3-compatible storage through the AWS SDK for Java 2 (RustFS locally), ClamAV for the antivirus, Apache Tika for type detection, TwelveMonkeys ImageIO and metadata-extractor for profile photos.
- **Security**: HS256 JWT access tokens, rotating opaque refresh tokens, Argon2id password hashing (Bouncy Castle).
- **Tests**: JUnit 5, Mockito, AssertJ, Testcontainers (PostgreSQL, RabbitMQ, Mailpit, RustFS, ClamAV), JaCoCo.
- **Tooling**: Maven wrapper, Docker Compose, GitHub Actions, gitleaks, Postman and newman.

## Tests

Unit tests (`*Test`) run without Docker. Integration tests (`*Tests`) start the API against real services in containers, so `./mvnw test` needs Docker running.

A full run takes a few minutes and several GB of memory. Do not run two full runs at the same time on one machine.

## Troubleshooting

**The API stops at startup with an error about the JWT secret.** `JWT_SECRET` is missing from `.env` or too short. Generate one with `openssl rand -base64 32`, and start the API from the project root so that `.env` is found.

**The API cannot reach RabbitMQ, ClamAV or the object storage after you pull new changes.** When some services of `compose.yaml` already run, the API does not start the ones added since. Run `docker compose up -d` once.

**An upload fails with 503 and "Antivirus unavailable".** The antivirus loads its signatures for a minute or two after it starts, and uploads are refused rather than stored unscanned until then. Wait and retry, or see [Run without the antivirus](#run-without-the-antivirus).

**Task endpoints answer 403 for an account that can log in.** The account's email is not verified yet, or an admin disabled it. Only enabled, verified accounts can work on tasks.

**A profile photo does not change after the upload.** The upload answers 202 and the photo is processed in the background: `avatarPending` stays `true` in the account until it is done. If it stays pending, check the `avatar.process.dead-letter` queue in the RabbitMQ console.

**An email never arrives.** Emails leave shortly after the request, in the background. Check Mailpit locally, then:
- the `outbox_messages` table: a row with no `published_at` is waiting for RabbitMQ, and `last_error` says why;
- the `mail.send.dead-letter` queue: a message lands there when the mail server kept failing.

## Contributing

- Branches follow Git flow: `features/<name>` is merged into `develop` through a pull request, and releases go through `release/<version>` to `main`.
- Commits follow Conventional Commits (`feat`, `fix`, `test`, `docs`, ...), and production code and its tests go in separate commits.
- [CLAUDE.md](CLAUDE.md) holds the detailed conventions: architecture rules, the traps to avoid, how to write comments, and how to test.
