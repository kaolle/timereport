# timereport

A Java 21 / Quarkus service for personal time reporting, invoicing inputs, and
customer reporting. Stages 1–3 implement the domain, PostgreSQL persistence, REST
API, and a mobile-first PWA with offline local storage and queued synchronization.
Conflict resolution is available in the UI; authentication and reports follow later.
The HTTP server currently binds to `127.0.0.1` only.

## Run locally

Requirements: JDK 21 or newer, PostgreSQL 17, and the Maven wrapper. Maven compiles
against Java 21. The `timereport` database must exist and be owned by the application's
PostgreSQL role (default `timereport`).

```shell
brew services start postgresql@17
# If your PostgreSQL authentication requires a password, enter it without echoing:
read -s 'DB_PASSWORD?Database password: '
export DB_PASSWORD
./mvnw quarkus:dev
```

The password prompt example is for macOS zsh. Omit it if local authentication does
not require a password. No credentials are committed. Configuration can be supplied
through environment variables or a local, ignored `.env` file:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/timereport` |
| `DB_USERNAME` | `timereport` |
| `DB_PASSWORD` | Empty (only appropriate for local authentication that allows it) |

Web app: `http://localhost:8080/`. API: `http://localhost:8080/api`.
Dev UI: `http://localhost:8080/q/dev/`.
The Flyway migration in `src/main/resources/db/migration/` runs at startup. It creates
`customer_codes`, `time_entries`, and `app_write_lock` plus Flyway's history table.
Migration cleaning is disabled. Add new migrations rather than editing applied ones.

## Mobile web app, offline storage, and synchronization (stage 4)

The Swedish, mobile-first UI provides a week view with a weekly total. Select a day
to enter or edit times, customer code, deduction, and comment. Valid form changes
save to IndexedDB automatically; no separate save action is needed. A day's first
entry suggests 30 minutes of lunch; subsequent entries suggest zero. Vacation marks
are stored in this browser's IndexedDB for offline access and included in the local
JSON copy; they are not synchronized to PostgreSQL.

On first use, connect to the server. If no customer codes exist, expand **Kundkoder
och installation** and add a real customer code. This action writes the code to the
server immediately and caches the catalogue locally. Existing daily entries are
downloaded for the selected day. Previously fetched codes and entries remain
available offline. Days not previously fetched can have an incomplete local view;
the server checks all entries when a queued change is uploaded.

IndexedDB stores entries and a pending-write queue in the same transaction. Changes
are saved locally first, then uploaded automatically when online or with **Synka tider
nu**. Refresh also attempts synchronization. Edits use server versions and deletes
retain tombstones until accepted. A local revision check protects edits made in
another tab. HTTP 409 conflicts preserve both versions and require an explicit choice;
overlapping entries must be edited before retrying. Rejected changes stay local and
can be retried manually after correcting the cause.

Wait for **Redo utan nät** before closing the app or disconnecting. The service worker
caches the app shell, not API responses. IndexedDB holds user data. Installation uses
the browser's install action; on iPhone use Safari's Share → Add to Home Screen.
Service workers need HTTPS, except for localhost/loopback development. Opening the
laptop's LAN IP over HTTP on a phone is not sufficient for offline installation;
a trusted HTTPS setup is needed for physical-device testing. Authentication and
public hosting are not part of this stage.

Local storage belongs to this browser profile and origin: `localhost` and
`127.0.0.1`, or different ports, do not share it. Avoid private browsing for ongoing
work. Clearing site data loses unsent entries. **Spara en lokal kopia** downloads JSON
containing entries, codes, and pending changes; it is not an invoice export or an
automatic backup/restore feature. Persistent browser storage is requested after a
save when supported, but the browser may decline it.

Web files live under `src/main/resources/META-INF/resources/`. There are no runtime
JavaScript dependencies or frontend build step. Modules separate calculation,
IndexedDB storage, API calls, and UI. Bump the shell cache version in `sw.js` whenever
changing shell assets. Updates activate after all existing app tabs/windows close;
they do not clear IndexedDB or force-reload an unsaved form.

### Web tests

```shell
npm ci
npx playwright install chromium
npm run test:unit
npm run test:web
```

Playwright starts an isolated static server on `127.0.0.1:4173` and mocks API data;
these browser tests never write to your PostgreSQL database. They cover offline
save/edit/delete, full browser restart, refresh protection, atomic write failure,
cross-tab conflicts, and 320/390/1280-pixel layouts. Screenshots and failure traces
are written under `test-results/`. The Quarkus tests separately verify HTTP assets
are included in the application. Physical iOS/Android testing is still required
before production use.

## API

All request/response bodies use JSON. DTOs and domain models are separate immutable
records with Lombok builders. For customer codes use URL-encoded path segments.

| Method and path | Purpose |
| --- | --- |
| `PUT /api/customer-codes/{code}` | Create, activate, or deactivate a predefined code |
| `GET /api/customer-codes` | List codes, including inactive ones |
| `PUT /api/time-entries/{uuid}` | Create or replace a time entry |
| `GET /api/time-entries/{uuid}` | Read a non-deleted entry |
| `GET /api/time-entries?from=2026-10-01&to=2026-10-31` | List entries in an inclusive date range, ordered by date/start |
| `DELETE /api/time-entries/{uuid}?expectedVersion=1` | Delete the version the client last read |

Successful PUTs return `200` with the saved object and `version`; DELETE returns
`204`. Invalid inputs return `400`, absent/deleted entries `404`, and stale versions,
reused IDs with different data, or overlapping intervals `409`. Application errors
have `code` and `message`; Bean Validation errors include field violations.

Create a customer code:

```shell
curl -sS -X PUT http://localhost:8080/api/customer-codes/FAIR1234 \
  -H 'Content-Type: application/json' \
  -d '{"active":true,"expectedVersion":0}'
```

Create a time entry with a client-generated UUID (the example uses a fixed UUID so
repeating the command demonstrates retry handling):

```shell
curl -sS -X PUT http://localhost:8080/api/time-entries/01aa9af4-753f-4ef3-aee5-a735ef08a513 \
  -H 'Content-Type: application/json' \
  -d '{"date":"2026-10-10","start":"08:00","end":"17:00","deductedMinutes":30,"customerCode":"FAIR1234","comment":"Implementation","expectedVersion":0}'
```

The response contains `workedMinutes: 510` and `version: 1`. To edit, PUT the complete
replacement body to the same URL with `expectedVersion: 1`; success returns version
2. To deactivate a customer code, PUT `active: false` with its current version.
Codes are never physically deleted, preserving historical references. Corrections
may retain an inactive code, but new entries or reassignment to one are rejected.

## Versioning and transactions

- New objects require `expectedVersion: 0`; updates/deletes require the last read
  version. Versions increase on every accepted update, even if values are unchanged.
- Repeating the same PUT with the same expected version and normalized content
  returns the previous result if that result is still current; it does not increment
  the version. A retry after a later change returns `409` rather than overwriting it.
- DELETE retains a tombstone and increments the version. Retrying that same deletion
  returns `204`; deleted IDs cannot be reused. Normal GET/list excludes tombstones.
- Validation and writes run within one transaction, locking a singleton database row.
  This deliberately serializes writes for this single-user service, including across
  application instances. A PostgreSQL exclusion constraint additionally prevents
  overlapping active intervals. Failed writes roll back completely.
- Offline synchronization uses the returned version as an optimistic concurrency
  token. A 409 triggers a GET of the current server entry; the UI preserves both
  values until the user chooses one. Do not expose this API publicly: user
  authentication and data ownership are not implemented yet.

## Architecture

Under `com.timereport.timeentry`:

- `domain`: immutable models and business rules, without framework dependencies.
- `application.port.in`: customer-code and time-entry use cases.
- `application.port.out`: repositories and atomic write transaction boundary.
- `application.service`: framework-independent orchestration and version checks.
- `adapter.in.rest`: JSON DTOs, mapping, request validation, and error responses.
- `adapter.out.persistence`: JDBC repositories and transactional implementation.
- `configuration`: CDI producers wiring application services to their ports.

Adapters depend inward on application/domain contracts. Lombok is compile-time only,
configured using its [Maven setup guide](https://projectlombok.org/setup/maven).
JDBC transactions and migration setup follow the Quarkus
[transaction](https://quarkus.io/guides/transaction/) and
[Flyway](https://quarkus.io/guides/flyway/) guides.

## Domain rules

- Customer codes are nonblank, trimmed, and case-sensitive. The application checks
  membership and active status against the predefined list.
- Time entries have a stable client-supplied UUID, date, local start/end times,
  deducted minutes, customer code, and optional comment. Edits retain the UUID.
- Times are whole-minute local wall-clock times in the business timezone (initially
  `Europe/Stockholm`), within one calendar day. They are not UTC sync timestamps.
- End must follow start. Deduction may range from zero to the interval length;
  zero net minutes is valid. An omitted/null deduction defaults to 30 minutes.
  Explicit zero means no deduction; omitted comments become empty strings.
- When splitting a day, clients must explicitly choose each entry's deduction.
  `WorkDay.suggestedDeductedMinutes()` suggests 30 for an empty day and zero otherwise;
  the API does not silently rewrite deductions on additional entries.
- Entries on one day cannot overlap, even across codes. Adjacent intervals are valid.
  WorkDay defensively copies collections and rejects duplicate IDs/different dates.
- Calculations and totals use whole minutes, without floating-point rounding.
  `08:00–17:00` less 30 minutes gives 510 minutes, or 8.5 hours.

## Build and tests

```shell
./mvnw test
./mvnw verify -DskipITs=false
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

Keep the entire `target/quarkus-app/` directory when running the packaged application.
Domain/application tests use plain JUnit and fake output ports. REST and persistence
tests use Quarkus Dev Services with an isolated PostgreSQL 17 container (Docker/Colima
must be running). They do not use the local `timereport` database by default. Avoid
setting `QUARKUS_DATASOURCE_*` overrides when running tests. The packaged smoke test
also starts against an isolated database. Coverage includes retries, conflicts,
concurrent writes, rollback, database constraints, and boundary validation.

For domain-only tests without Docker:

```shell
./mvnw test -Dtest=CustomerCodeTest,TimeEntryTest,WorkDayTest,TimeReportingServiceTest
```

On this laptop Java 17 is currently the shell default. Select Java 25 (installed)
when running Maven, or install/select Java 21:

```shell
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw quarkus:dev
```

Native builds remain available with `./mvnw package -Dnative` or
`./mvnw package -Dnative -Dquarkus.native.container-build=true`.
