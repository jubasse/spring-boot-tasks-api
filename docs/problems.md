# Error responses

Every error of the API comes back as a problem document ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), with the `application/problem+json` content type:

```json
{
  "type": "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md#email-taken",
  "title": "Email already in use",
  "status": 409,
  "detail": "A user with email ada@example.com already exists",
  "instance": "/api/v1/users"
}
```

- **`type`** tells you what went wrong. Match on it in your code. When it is absent, the status code says it all (see [Errors without a type](#errors-without-a-type)).
- **`title`** is a short summary that stays the same for every occurrence of a type.
- **`detail`** explains this occurrence, for a person to read. Show it, but do not parse it: its wording can change, and it follows the `Accept-Language` header of the request.
- **`instance`** is the path of the request.

A problem can carry more members than these; ignore the ones you do not use.

## validation-error

**Status 400, title "Invalid request".** One or more values of the request are invalid. The request changed nothing. The `errors` member lists each invalid value:

```json
{
  "type": "https://github.com/jubasse/spring-boot-tasks-api/blob/main/docs/problems.md#validation-error",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more values of the request are invalid: see errors.",
  "instance": "/api/v1/users",
  "errors": [
    { "detail": "must be a well-formed email address", "pointer": "#/email" },
    { "detail": "size must be between 8 and 128", "pointer": "#/password" }
  ]
}
```

Each item has a `detail` and one of:
- **`pointer`**: a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) to the value in the JSON body, such as `#/email` or `#/items/0/name`. `#` alone means the body as a whole.
- **`parameter`**: the name of a query, path, form or multipart parameter, such as `status` in `GET /api/v1/tasks?status=OPEN`, `sort` naming a property that cannot be sorted on, `id` in a path, or `files` on a comment with too many files.

Fix every listed value and send the request again.

## invalid-token

**Status 400, title "Invalid or expired token".** The token of an email verification link (`POST /api/v1/auth/verify-email`) or of a password reset link (`POST /api/v1/auth/password-reset/confirm`) is unknown, expired or already used.

Ask for a new email: `POST /api/v1/auth/verify-email/resend` or `POST /api/v1/auth/password-reset/request`.

## email-taken

**Status 409, title "Email already in use".** Sign-up (`POST /api/v1/users`) with an email that an account already uses, including an account that was deleted less than 30 days ago.

Sign in with that email, reset its password, or sign up with another email.

## reference-taken

**Status 409, title "Task reference already in use".** A task created with a reference that another task already has, including a deleted task: references are never reused.

Choose another reference.

## email-already-verified

**Status 409, title "Email already verified".** A new verification email was requested (`POST /api/v1/auth/verify-email/resend`) for an account whose email is already verified.

Nothing to do: the account can already work on tasks.

## version-conflict

**Status 409, title "Changed by another request".** Another request changed the same task at the same moment, and this request was not applied.

Load the task again, check that your change still makes sense, and send it again.

## assignee-not-active

**Status 422, title "Assignee not active".** A task was assigned to an account that cannot work on tasks: its email is not verified, or the account is disabled or deleted.

Choose another assignee, or ask an admin about that account.

## invalid-mention

**Status 422, title "Invalid mention".** A comment mentions (`<@user-id>`) an account that does not exist or cannot work on tasks. Editing a comment checks only the mentions it adds.

Remove the mention, or mention another user.

## infected-file

**Status 422, title "File rejected by the antivirus".** An uploaded file (task attachment, comment file or profile photo) contains a threat. It was not stored.

Check the file on your side before uploading it again.

## invalid-image

**Status 422, title "Invalid image".** A profile photo cannot be used: it is wider or taller than 10,000 pixels, larger than 40 megapixels, or its header cannot be read.

Upload a smaller image, or save it again in JPEG, PNG or WebP.

## Errors without a type

These errors have no `type` member (RFC 9457 reads that as `about:blank`), and their `title` is the status phrase. The status code tells you what to do.

| Status | When | What to do |
|---|---|---|
| 400 Bad Request | The body is not valid JSON or not the JSON object the endpoint expects, or an uploaded file is empty | Fix the request |
| 401 Unauthorized | Wrong email or password, or an invalid refresh token. Without a valid access token, the response has no body and a `WWW-Authenticate` header | Sign in again |
| 403 Forbidden | The account may not do this. The response has no body | Do not retry |
| 404 Not Found | The task, comment, attachment or user does not exist, or no endpoint matches the path | Check the id or the path |
| 405 Method Not Allowed | The endpoint does not accept this HTTP method | Check the method |
| 409 Conflict | The request conflicts with data that changed at the same moment | Load the data again and retry |
| 413 Content Too Large | A file is larger than its limit | Upload a smaller file |
| 415 Unsupported Media Type | The file type is not accepted, whatever its name or declared type | Upload an accepted type |
| 429 Too Many Requests | Too many attempts from your address or for that email | Wait for the number of seconds in the `Retry-After` header |
| 500 Internal Server Error | An unexpected failure on the server; the detail says nothing more | Retry later, and report it if it persists |
| 503 Service Unavailable | The antivirus or the file storage is unavailable | Retry later |
