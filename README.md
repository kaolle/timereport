# Tidrapporten

A Java 21 / Quarkus service for personal time reporting, PDF invoicing, email
delivery, and customer reporting. Stages 1–3 implement the domain, PostgreSQL persistence, REST
API, and a mobile-first PWA with offline local storage and queued synchronization.
The UI includes conflict resolution, account registration, and JWT authentication.
Monthly reports are available. [Deploy to Cloud Run via GitHub](CLOUD_RUN.md) using
Cloud Build and Google Buildpacks, with Neon PostgreSQL and Brevo SMTP. Development
binds to `127.0.0.1`; production uses `0.0.0.0:8080`.

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
Flyway migrations in `src/main/resources/db/migration/` run at startup. They create
account, session, token, customer-code, time-entry, and vacation tables.
**Upgrade notice:** V3 clears existing time entries, customer codes, and vacation
marks before adding account ownership. This reset is intentional for the existing
test data. Migration cleaning is disabled; add new migrations rather than editing
applied ones. V4 makes customer codes optional without deleting existing data.

Applied migration files must remain unchanged, including formatting: changing spaces
or comments can change Flyway checksums. Exclude applied migrations from automatic
SQL formatting and put schema changes in a new migration. For an existing checksum
mismatch, verify the installed schema before using Flyway `repair`; repair updates
migration history and does not apply changed SQL. Keep repair a one-time operation,
rather than enabling it automatically on startup.

In IntelliJ IDEA, use **Settings → Editor → Code Style → Formatter → Do not format**
and add `**/db/migration/*.sql` to protect migrations from reformatting, including
save and commit actions. See the [IntelliJ formatter exclusions](https://www.jetbrains.com/help/idea/reformat-and-rearrange-code.html#exclude_file_from_reformat).
A clean Git working tree does not rule out a checksum mismatch: a migration can
have been executed locally before formatting was included in its first commit.

### Create your first account

Open `http://localhost:8080/`, select registration, and enter an email address.
In development, mail is mocked: find the verification link in the Quarkus console,
open it, and choose a password of 15–128 characters to activate your account.
Then log in. No SMTP account is needed locally. Password-reset links also appear
in the development console and expire after 30 minutes.

Use `localhost` consistently: the configured public origin must match the browser
origin, including its port. The default is `http://localhost:8080`.

## Mobile web app, offline storage, and synchronization (stage 4)

The top menu separates **Tider**, **Månadsrapport**, **Fakturering**, **Konto**, and
**Installation**. **Fakturering** has four tabs: **Ny faktura**, **Fakturor**, **Ditt företag**, and
**Kunder**. Use Left/Right arrows or Home/End while a tab has focus to switch panels.
Customer-code billing assignments are shown inside each customer card; the code list
remains there for managing active and removed codes.
**Tider** is always the default after login or opening/reloading the app.
Switching views during a session preserves the selected week and any form input.

The Swedish, mobile-first UI provides a week view with a weekly total. On desktop,
all seven days remain on one row with the selected day's form below the whole week.
After login, page reload, or week navigation, no day is selected and the form stays
closed until a day is explicitly chosen.

On mobile, the form sits immediately below the selected day. Select a day to enter
or edit times, customer code, deduction, and comment. Selecting a day or reopening
its form focuses **Började**. Time fields use direct keyboard entry with no picker:
enter `08:30` or `0830` and Tab to the next field. Day cards show the first interval
in chronological order and the net day total; a badge such as **2 tider** marks
multiple registrations.

Valid form changes save to IndexedDB automatically, except that a new customer code
requires explicit confirmation. A day's first entry suggests 30 minutes of lunch;
subsequent entries suggest zero. Vacation marks are stored in IndexedDB for offline
access, included in the local JSON copy, and synchronized to PostgreSQL.

On first use, connect to the server and log in. Customer code is optional. Use
**Välj kod…** beside the field to select an existing code, or type a new code.
After a 0.7-second typing pause, an unknown code shows **Spara kundkod ”kod”**;
confirm it before the code and time are saved. Existing codes and entries without
a code still autosave. New codes are created on synchronization, and confirmation
also works offline. The code list under **Fakturering → Kunder** can also add codes
directly online.
Previously fetched codes and entries remain available offline. Days not previously
fetched can have an incomplete local view; the server checks all entries when a
queued change is uploaded.

**Ta bort** appears as soon as a registration has been saved locally, including a
new autosaved registration. Confirm deletion to remove it; merely clearing form
fields does not delete a saved entry. **+ Ny tid** opens a fresh form while keeping
the saved registration. There is no cancel-edit action because accepted edits are
already autosaved. Vacation checkboxes mark or unmark a day; marking a day with
entries asks for confirmation before deleting those entries.

IndexedDB stores entries and a pending-write queue in the same transaction. Changes
are saved locally first after a 0.7-second typing pause. Automatic upload waits
about five seconds after the latest saved edit, combining repeated edits to the same
entry into the latest queued version. Five seconds is an upload delay after editing,
not a bidirectional polling interval. Opening the app, reconnecting, and changing
weeks automatically upload pending changes and fetch the displayed week, respecting
a pending upload delay. Returning to the app also refreshes that week and any open
monthly report; return events are throttled to at most once per 30 seconds.

The main view only shows a compact status when changes are waiting or an error
needs attention. **Konto → Fler alternativ** contains manual sync/refresh, a local
JSON copy, and offline readiness for occasional use. Customer codes are managed under
**Fakturering → Kunder** and can also be created while entering time. **Synka tider nu**
saves valid pending form changes and uploads immediately; unconfirmed new codes
remain unsaved. An in-flight request can finish while new
edits are being saved locally.

Edits use server versions and deletes retain tombstones until accepted. A local
revision check protects edits made in another tab. HTTP 409 conflicts preserve both
versions and require an explicit choice; overlapping entries must be edited before
retrying. Rejected changes stay local and can be retried after correcting the cause.

Installation instructions are available under **Installation**, which contains only
information. Offline readiness is available under **Konto → Fler alternativ**. Wait for **Redo utan nät** before first offline use. The service worker
caches the app shell, not API responses. While online, HTML, scripts and styles
are fetched from the server and their cached copies are refreshed; cached assets
are used when the network is unavailable. IndexedDB holds user data. Installation uses
the browser's install action; on iPhone use Safari's Share → Add to Home Screen.
Service workers need HTTPS, except for localhost/loopback development. Opening the
laptop's LAN IP over HTTP on a phone is not sufficient for offline installation;
a trusted HTTPS setup is needed for physical-device testing.

To refresh a stale app shell while online, use the refresh button beside the
version in the header. If an older cached screen does not show that button, open
`/force-update.html` and choose **Hämta senaste versionen**. Use the same procedure
if new controls appear but do not respond: an older worker may still serve old
JavaScript alongside updated HTML. It clears only the service worker and app shell cache;
IndexedDB time entries remain intact.

IndexedDB databases and queues are separated by account. The previous shared
`timereport-local` database is no longer used or automatically imported. Previously
used accounts can continue editing cached data offline; synchronization requires a
valid session. An expired session preserves pending changes until login. Logging out
requires a connection to revoke the session; it hides, but does not erase, cached
account data. Local data is not encrypted by the application.

Local storage belongs to this browser profile and origin: `localhost` and
`127.0.0.1`, or different ports, do not share it. Avoid private browsing for ongoing
work. Clearing site data loses unsent entries. **Spara en lokal kopia** downloads JSON
containing entries, codes, and pending changes; it is not an invoice export or an
automatic backup/restore feature. Persistent browser storage is requested after a
save when supported, but the browser may decline it.

Web files live under `src/main/resources/META-INF/resources/`. There are no runtime
JavaScript dependencies or frontend build step. Modules separate calculation,
IndexedDB storage, API calls, and UI. When changing shell assets, bump
`application-version` in `index.html` and `force-update.html`, the asset URL versions
in those pages, the invoicing module import in `js/app.js`, and the delivery module
import in `js/invoicing-settings.js`. The service worker
cache version comes from the registration URL's `v` parameter. Updates activate after all existing app tabs/windows close;
they do not clear IndexedDB or force-reload an unsaved form.

### Web tests

```shell
npm ci
npx playwright install chromium
npm run test:unit
npm run test:web
```

Playwright starts an isolated static server on `127.0.0.1:4173` and mocks API data;
these browser tests never write to your PostgreSQL database. The authentication
suite checks memory-only access tokens, refresh, account-separated offline queues,
session expiry, logout, and reset links. The offline suite covers autosave, full
browser restart, pending edits and deletion tombstones, concurrent edits, storage
rollback, and mobile/desktop layouts. Report tests cover month boundaries, ISO
weeks, per-customer totals, pending local changes, incomplete offline data, and
month-switch races. UX tests verify explicit day selection, focus, direct keyboard
time entry, and responsive week layout. `npm run test:web` runs all browser suites.
Screenshots and failure traces are written under `test-results/`. Physical
iOS/Android testing is still required before production use.

## Customer codes

The code list under **Fakturering → Kunder** lists active codes. **Ta bort** asks for
confirmation and inactivates the code using its current server version; it disappears from normal
selection for new entries. Existing time entries and monthly reports retain the
code. **Borttagna kundkoder → Återställ** makes an inactive code available again.
Adding, removing, and restoring codes from this section require a connection. Removal
is disabled while local changes using the code are awaiting synchronization.
Historical entries can still retain an inactive code when corrected. No entries
are deleted and no database migration is needed.

## Monthly reports

Select **Månadsrapport** in the top menu and choose a month. The report
lists each ISO week (Monday–Sunday), with worked time per customer code, a weekly
total, and the month total. Only dates inside the selected month contribute to
boundary weeks. Times without a code appear under **Utan kundkod**; empty weeks
show zero. Totals use net worked minutes after lunch/deductions, displayed as hours
and minutes. Deleted entries and vacation days contribute no worked time.
Weeks with vacation show the marked dates and **Semester hela arbetsveckan** when
all five weekdays are marked within the selected month. The month also shows a
count of marked vacation days, including any marked weekends.

Selecting the report fetches the full month's time entries and vacation days through the existing
account-authenticated range API. Local pending edits, additions, and deletions are
preserved and included in the totals. **Hämta månadens tider** refreshes that month;
it does not upload pending changes. Report requests are made when the report view opens,
the month changes, or a refresh is requested, rather than for every autosaved edit.
The visible totals update when local entries change.

A successfully fetched month is cached per account for offline use. Offline reports
identify their cached source; a month that has not been fetched warns that totals
may be incomplete. Cached data can still be older than changes made on another
device. The IndexedDB upgrade preserves existing entries and queues. No SQL migration
or CSV export is introduced by this feature.

## Faktureringsinställningar (etapp 1)

Choose **Fakturering → Ditt företag** while online to configure seller details and
the next invoice number. Use **Fakturering → Kunder** to add customers and manage
their billing details. Each customer can have several customer codes, with a service
description, hourly rate, and VAT rate per code. The optional customer field
**Kontaktpersonens e-post** supplies the default CC address. Codes can be added in the customer
form, in the customer's code section, or while registering time. Their optional
short name appears beside the code in the time-entry selector, including offline
after the label has been cached. A customer code is required for a time entry to be
included in an invoice. Unassigned codes remain available for time reporting and
are excluded from a customer's invoice draft.

Save the seller details with **Spara fakturauppgifter** before previewing or issuing
an invoice. These settings belong to the signed-in account and database; local
development settings are not automatically copied to production. If the profile
is missing, the draft links to **Ditt företag** and keeps edited invoice rows when
switching tabs. Preview errors appear beside the action buttons and receive focus.
Changing saved company details requires a new PDF preview before issuing.

## Ny faktura (etapp 2)

Choose **Fakturering → Ny faktura**, select a month and customer, then choose
**Hämta fakturaunderlag**. Review and adjust the invoice lines, preview the PDF, then
choose **Godkänn och utfärda fakturan**. The preview includes billable hours and
amounts per customer code and ISO week, with net, VAT, and gross totals. You can edit
descriptions, hours, rates, and VAT in the draft; those edits do not change time
entries. The weekly breakdown starts collapsed; **Visa tidsunderlag** opens it and
**Dölj tidsunderlag** collapses it again. Entries without a code, without a billing
assignment, or assigned to another customer remain listed separately. Only synchronized entries are used; the page warns
if local entries for that month remain unsynchronized. A permanent number is assigned
only when the reviewed invoice is issued. The next number is configured under
**Ditt företag** (for example, 1061); issuing advances it automatically and it cannot
be lowered. The issued invoice is stored as an immutable snapshot and PDF. The same
customer cannot be invoiced twice for the same month.

Invoice action buttons share the same font, text size, height, and spacing.
Primary actions use dark green, secondary actions light green, and removal actions
red. On narrow screens action buttons use the full available width. The PDF preview
action is labelled **Förhandsgranska PDF**.

## Invoice delivery and payment status

After issuing, a delivery dialog offers independent **Ladda ner fakturan som PDF**
and **Skicka fakturan via e-post** checkboxes. Download is selected by default;
email must be selected explicitly. The recipient defaults to the customer's saved
invoice email and can be changed for that submission. Closing leaves the invoice
issued and available under **Fakturering → Fakturor**.

The delivery dialog loads default copy recipients for the selected invoice:
the customer's **Kontaktpersonens e-post** becomes CC and the company's **E-post**
becomes BCC. Defaults use the current customer/company settings, falling back to
the archived invoice when a record is unavailable. CC and BCC can be edited for
each submission; BCC is hidden from other email recipients. Enter comma-separated
addresses or clear a field to omit those copies. Each list allows up to ten
addresses. Duplicates across To, CC and BCC are removed. If defaults cannot be
loaded, reopen the dialog before sending; PDF download remains available.

The invoice list groups all account invoices into **Förfallna**, **Obetalda**, and
**Betalda**, showing gross and VAT totals separately per currency. Unpaid invoices
become overdue the day after their due date. **Betald** is a manual payment marker;
**Skickad** is set when SMTP accepts an email and can also be marked manually for
delivery by other means. Both are reversible; concurrent status edits return `409`
and refresh the list. Status changes never replace the issued snapshot or PDF.

Emails attach the stored PDF with sender name **Företagsnamn via Tidrapporten**.
`INVOICE_MAIL_FROM` optionally selects a separate provider-approved sender address;
otherwise `MAIL_FROM` is used. Reply-To uses the current company profile's email,
falling back to the issued snapshot. The message explicitly names this contact
address: **Vid frågor kan du skicka mejl till …**. Set it under
**Fakturering → Ditt företag → E-post** before sending. Account
verification/reset messages use the display name **Tidrapporten**.

Each email request has a UUID `requestId`. Repeating it returns the recorded state
without resending; reusing it for another recipient, copy list or invoice returns `409`. A lost
HTTP response retains the ID in the dialog. Explicit resends use a new ID and ask
for confirmation. Attempts are stored before SMTP, with `SENDING`, `SENT`, or
`FAILED` results. There is no automatic SMTP retry: a timeout or crash can leave an
ambiguous result. Check Brevo before an explicit resend. A pending attempt blocks
new sends for five minutes; each invoice permits five attempts per hour.
`SENT` means SMTP acceptance, not delivery to the inbox. Bounce processing remains
in [INFRA_TODO.md](INFRA_TODO.md). Development/test uses Quarkus's mock mailbox,
so no real email is sent. V8 adds delivery attempts and mutable status metadata;
earlier migration scripts are unchanged. V9 adds the customer's contact email and
stores the exact CC/BCC lists on each delivery attempt. For API clients, omitted
or null copy lists use the defaults; explicit empty lists suppress those copies.

## Authentication

By default, registration is email first: request a verification link, open it, choose
a password, and then log in. Passwords are chosen only after the email link is opened,
preventing someone from activating an account with a password supplied by another
registrant. For temporary setup only, `AUTH_EMAIL_VERIFICATION_REQUIRED=false` allows
registration with email and password and logs the new account in directly. This skips
proof of email ownership and should be turned back on after setup. Requesting another
verification link invalidates the previous link; registering an already activated
address does not change its password.

Access tokens are RS256 JWTs valid for **5 minutes**, kept only in JavaScript memory.
The opaque refresh token is stored in an **HttpOnly, SameSite=Strict** cookie scoped
to `/api/auth`, with `Secure` enabled in production. Sessions last **30 days from
login**, without extending that deadline on refresh. Each refresh rotates the token;
reuse revokes the session. Only hashes of refresh and email tokens are persisted.
Logout revokes that session; password changes and resets revoke all account sessions.
Protected data requests also check session validity, so revocation takes effect
without waiting for JWT expiry. Passwords use salted PBKDF2-HMAC-SHA256 (600,000 rounds).

On return to the app, including a restored browser page, expired access tokens are
renewed using the refresh cookie. Renewal allows 30 seconds for a cold start; the
data request's timeout starts after renewal. Registration settings load independently.
A temporary renewal/network failure during startup lets a remembered account open
its local data; an authentication rejection still shows the login screen. Cached
account information never authorizes server access.

All POST authentication requests require `X-Requested-With: timereport`. When an
`Origin` header is present, it must exactly match `PUBLIC_URL`. Authentication is
rate limited; `429` includes `Retry-After`. Registration and reset requests return
generic messages to avoid disclosing whether an email is registered.

| Method and path                  | JSON body / behavior                                                                                                                                                      |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/auth/register`        | Normally `email`, sends verification link, returns `202`; with `AUTH_EMAIL_VERIFICATION_REQUIRED=false`, accepts `email` and `password`, verifies and logs in immediately |
| `GET /api/auth/config`           | Returns whether email verification is required for registration                                                                                                           |
| `POST /api/auth/verify-email`    | `token`, chosen `password`; activates account, returns `204`                                                                                                              |
| `POST /api/auth/login`           | `email`, `password`; returns access token, expiry, account, and refresh cookie                                                                                            |
| `POST /api/auth/refresh`         | No body; uses refresh cookie and returns a new token pair                                                                                                                 |
| `POST /api/auth/logout`          | No body; revokes session and clears cookie                                                                                                                                |
| `POST /api/auth/forgot-password` | `email`; requests reset link, returns `202`                                                                                                                               |
| `POST /api/auth/reset-password`  | `token`, new `password`; returns `204`                                                                                                                                    |
| `POST /api/auth/change-password` | `currentPassword`, `newPassword`; requires bearer token                                                                                                                   |
| `GET /api/auth/me`               | Requires bearer token; returns account ID and email                                                                                                                       |

Verification and reset links are single use and expire after 30 minutes. Their
secret is passed in a URL fragment and removed from the address bar by the UI.
The application uses the verified account identity to scope every business query;
clients cannot choose ownership by supplying an `account_id`.

### Test authentication locally

Start the service with `./mvnw quarkus:dev` and use `http://localhost:8080/`:

1. Register an email address; open the verification link printed in the Quarkus console.
2. Choose a password of 15–128 characters, then log in. An unverified account cannot log in.
3. Reload the page; the refresh cookie restores the session without persisting an access token.
4. Use **Glömt lösenord** to request a reset link in the Quarkus console. The local
   mailer is mocked, and a link is generated only when that email exists in the database
   used by the running dev process. By default that is `localhost:5432/timereport`, not
   the Cloud Run/Neon database. Resetting or changing the password requires a new login
   and invalidates existing sessions.
5. Log in with a second account to check that times, codes, vacations, and offline queues
   are separate. Offline editing requires a previously used account and cached app shell.

In browser developer tools, `timereport_refresh` appears under cookies and is
HttpOnly; it is not exposed to JavaScript. Local storage contains the last account's
ID/email, while IndexedDB contains account-specific work data. Access tokens appear
only in request authorization headers and memory. Never paste real tokens into
external debugging tools.

## Production configuration and email

Before running the packaged application or deploying to Cloud Run, configure the
variables below. Production has no fallback database credentials, public URL or sender.
See [.env.production.example](.env.production.example) for a placeholder-only reference;
this file is not loaded automatically and must never contain real credentials.

| Variable                               | Value                                                                                   |
|----------------------------------------|-----------------------------------------------------------------------------------------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection settings                                                          |
| `DB_POOL_MAX_SIZE`                     | Maximum JDBC connections per instance (default `8`); budget across all instances        |
| `PUBLIC_URL`                           | Exact public HTTPS origin, such as `https://time.example.com`, without a trailing slash |
| `JWT_PRIVATE_KEY`                      | Complete RSA private key in PKCS#8 PEM format, injected from Secret Manager             |
| `JWT_PUBLIC_KEY`                       | Complete matching RSA public key in X.509 PEM format, injected from Secret Manager      |
| `SMTP_HOST`, `SMTP_PORT`               | SMTP host and STARTTLS port (default `587`)                                             |
| `SMTP_USERNAME`, `SMTP_PASSWORD`       | SMTP credentials                                                                        |
| `MAIL_FROM`                            | Sender address accepted by your mail provider                                           |
| `INVOICE_MAIL_FROM`                    | Optional invoice sender address on an authenticated domain; defaults to `MAIL_FROM`     |

Development/test keys are generated automatically when no keys are configured;
production needs stable keys shared by all instances. Cloud Run should inject the
complete PEM values as `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`. Keep their BEGIN/END
lines and actual line breaks; do not add quotes or replace line breaks with literal `\n`.
Pin both secrets to explicit versions when deploying.

For local development with your own PEM files, use the alternative path variables:

```shell
JWT_PRIVATE_KEY_PATH=/path/to/dev-private.pem \
JWT_PUBLIC_KEY_PATH=/path/to/dev-public.pem \
./mvnw quarkus:dev
```

File paths also work when running the packaged application locally. Set either a
complete value pair or a complete file-path pair. Production startup rejects missing,
partial or mixed configuration, unreadable/invalid keys, mismatched pairs and RSA keys
below 2048 bits, without logging key contents. Paths must be readable filesystem paths,
not URLs. Startup also rejects invalid HTTPS origins and insecure refresh cookies.
Generate a pair outside the repository, for example:

```shell
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /tmp/timereport-private.pem
openssl pkey -in /tmp/timereport-private.pem -pubout -out /tmp/timereport-public.pem
```

Keep production keys and credentials in Secret Manager and inject them as secret-backed
environment variables into Cloud Run. No JWT volume mounts are needed. Never commit
the values. Production mail uses STARTTLS and is not mocked.
For Brevo, use `SMTP_HOST=smtp-relay.brevo.com`, `SMTP_PORT=587`, the SMTP login
and an SMTP key (not the API key), plus a verified sender for `MAIL_FROM`.
See [Brevo SMTP configuration](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP).

The production listener uses the default `0.0.0.0:8080`. Set Cloud Run's container
port to `8080`; no custom `PORT` environment variable is needed. Cloud Run terminates HTTPS and
forwards HTTP to the container; refresh cookies remain `Secure`, `HttpOnly` and
`SameSite=Strict`. Serve the UI and API on the same origin and set `PUBLIC_URL` to
that exact origin. Account links use this configured URL, never the request Host header.
See [Cloud Run's container contract](https://docs.cloud.google.com/run/docs/container-contract).

Production uses INFO console logging without ANSI colors or file/access logging;
SMTP debug is disabled. Do not enable protocol/body/header debug logging in production:
it can expose passwords, tokens or account links. Existing API responses use `no-store`.
Forwarded headers remain untrusted. Authentication rate limits currently use the direct
peer IP, so clients behind a shared proxy can share an IP bucket. Before public rollout,
verify this behavior on Cloud Run and choose a trusted client-IP strategy if needed;
do not enable arbitrary forwarded-header trust.

Build the JVM artifact with `./mvnw package` and build the existing non-root image with
`docker build --platform linux/amd64 -f src/main/docker/Dockerfile.jvm -t timereport:prod .`
(the platform flag also supports building for Cloud Run from an Apple Silicon laptop).
For GitHub deployment, `cloudbuild.yaml` uses Google Buildpacks instead of a Dockerfile.
Follow [CLOUD_RUN.md](CLOUD_RUN.md) for the first build, Neon JDBC settings, Secret
Manager environment variables and Cloud Run creation. Existing deployments using
`JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH` secret references should rename those
references to `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`; the Secret Manager values stay unchanged.
Health endpoints are `/q/health/started`, `/q/health/live` and `/q/health/ready`;
readiness also checks the database. Do not use readiness as a liveness probe.

For local testing with real SMTP, explicitly set `QUARKUS_MAILER_MOCK=false` and
`QUARKUS_MAILER_HOST`, `QUARKUS_MAILER_PORT`, `QUARKUS_MAILER_USERNAME`,
`QUARKUS_MAILER_PASSWORD`, and `QUARKUS_MAILER_START_TLS=REQUIRED`; the `SMTP_*`
mappings above apply only to the production profile.

Gmail can be used with `smtp.gmail.com:587`, your full email address as the username
and sender, and an app password when your account supports it. App passwords require
two-step verification and can be unavailable for some account configurations; see
[Google's app-password instructions](https://support.google.com/mail/answer/185833).
Use the [Google Workspace SMTP options](https://support.google.com/a/answer/176600?hl=en)
if your account is managed through Workspace. A Cloud Run deployment still needs
an email provider and the configuration above.

## API

All request/response bodies use JSON. DTOs and domain models are separate immutable
records with Lombok builders. For customer codes use URL-encoded path segments.
All endpoints below require
`Authorization: Bearer <access-token>` and operate on the signed-in account.

| Method and path                                            | Purpose                                                                                                        |
|------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| `GET /api/invoicing/profile`                               | Read seller billing profile; returns `204` when not configured                                                 |
| `PUT /api/invoicing/profile`                               | Create or replace the account's seller billing profile                                                         |
| `GET /api/invoicing/customers`                             | List billing customers                                                                                         |
| `POST /api/invoicing/customers`                            | Create a billing customer                                                                                      |
| `PUT /api/invoicing/customers/{uuid}`                      | Replace a billing customer's details                                                                           |
| `DELETE /api/invoicing/customers/{uuid}`                   | Delete an unassigned billing customer                                                                          |
| `GET /api/invoicing/customer-codes`                        | List customer codes and their optional billing assignment                                                      |
| `PUT /api/invoicing/customer-codes/{code}`                 | Assign customer, service description, hourly rate, and VAT                                                     |
| `DELETE /api/invoicing/customer-codes/{code}`              | Remove billing assignment while retaining the customer code                                                    |
| `GET /api/invoicing/draft?month=2026-10&customerId={uuid}` | Read-only monthly source data for a customer, grouped by ISO week and code                                     |
| `POST /api/invoicing/draft/pdf`                            | Generate an unissued PDF preview with optional line adjustments                                                |
| `GET /api/invoicing/invoice-numbering`                     | Read the next invoice number (defaults to 1)                                                                   |
| `PUT /api/invoicing/invoice-numbering`                     | Set or increase the next invoice number                                                                        |
| `POST /api/invoicing/invoices`                             | Issue the reviewed month/customer invoice and return its PDF; accepts the same line adjustments as the preview |
| `GET /api/invoicing/invoices/{number}/pdf`                 | Download the account's issued PDF again                                                                        |
| `GET /api/invoicing/invoices`                              | List account invoices, due dates, gross/VAT amounts, status versions, and latest email attempt                 |
| `POST /api/invoicing/invoices/{number}/email`              | Send the archived PDF; body: `recipient`, UUID `requestId`, optional `cc`/`bcc` arrays                         |
| `GET /api/invoicing/invoices/{number}/delivery-defaults`   | Read default CC and BCC lists for the account's issued invoice                                                 |
| `PUT /api/invoicing/invoices/{number}/status`              | Set manual `sent`/`paid` booleans with `expectedVersion`; returns updated status                               |
| `PUT /api/customer-codes/{code}`                           | Create, activate, or deactivate a customer code                                                                |
| `GET /api/customer-codes`                                  | List codes, including inactive ones                                                                            |
| `PUT /api/time-entries/{uuid}`                             | Create or replace a time entry                                                                                 |
| `GET /api/time-entries/{uuid}`                             | Read a non-deleted entry                                                                                       |
| `GET /api/time-entries?from=2026-10-01&to=2026-10-31`      | List entries in an inclusive date range, ordered by date/start                                                 |
| `DELETE /api/time-entries/{uuid}?expectedVersion=1`        | Delete the version the client last read                                                                        |
| `GET /api/vacation-days?from=2026-10-01&to=2026-10-31`     | List vacation marks in an inclusive date range                                                                 |
| `PUT /api/vacation-days/{date}`                            | Mark a vacation day (`YYYY-MM-DD`), returns `204`                                                              |
| `DELETE /api/vacation-days/{date}`                         | Remove a vacation mark, returns `204`                                                                          |

Customer-code and time-entry PUTs return `200` with the saved object and `version`;
their DELETE returns `204`. Vacation writes return `204`. Invalid inputs return `400`, absent/deleted entries `404`, and
stale versions,
reused IDs with different data, or overlapping intervals `409`. Application errors
have `code` and `message`; Bean Validation errors include field violations.

After login, set `ACCESS_TOKEN` to the returned access token for these shell examples.
Create a customer code:

```shell
curl -sS -X PUT http://localhost:8080/api/customer-codes/FAIR1234 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"active":true,"expectedVersion":0}'
```

Create a time entry with a client-generated UUID (the example uses a fixed UUID so
repeating the command demonstrates retry handling):

```shell
curl -sS -X PUT http://localhost:8080/api/time-entries/01aa9af4-753f-4ef3-aee5-a735ef08a513 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"date":"2026-10-10","start":"08:00","end":"17:00","deductedMinutes":30,"customerCode":"FAIR1234","comment":"Implementation","expectedVersion":0}'
```

The response contains `workedMinutes: 510` and `version: 1`. To edit, PUT the complete
replacement body to the same URL with `expectedVersion: 1`; success returns version
2. To deactivate a customer code, PUT `active: false` with its current version.
Codes are never physically deleted, preserving historical references. Corrections
may retain an inactive code, but new entries or reassignment to one are rejected.

For time-entry PUTs, `customerCode` may be omitted, `null`, or blank; the response
then contains `customerCode: null`. A nonblank code is trimmed, and an unknown code
is created as active within the same account-scoped transaction as the time entry.
Replacing an existing entry with an empty code removes its customer assignment.
The explicit new-code confirmation is a web UI behavior; direct API clients create
new codes by sending them in the PUT body.

## Versioning and transactions

- New objects require `expectedVersion: 0`; updates/deletes require the last read
  version. Versions increase on every accepted update, even if values are unchanged.
- Repeating the same PUT with the same expected version and normalized content
  returns the previous result if that result is still current; it does not increment
  the version. A retry after a later change returns `409` rather than overwriting it.
- DELETE retains a tombstone and increments the version. Retrying that same deletion
  returns `204`; deleted IDs cannot be reused. Normal GET/list excludes tombstones.
- Validation and writes run within one transaction, locking the current account row.
  This serializes writes per account across application instances. A PostgreSQL
  exclusion constraint additionally prevents overlapping active intervals. Failed writes roll back completely.
- Offline synchronization uses the returned version as an optimistic concurrency
  token. A 409 triggers a GET of the current server entry; the UI preserves both
  values until the user chooses one. Authentication failures preserve queued changes
  for a later login.

## Architecture

Business capabilities live under `com.timereport.timeentry`,
`com.timereport.authentication`, and `com.timereport.invoicing`, each following
ports and adapters:

- `domain`: immutable models and business rules, without framework dependencies.
- `application.port.in`: authentication, account identity, time-entry, code, vacation, and billing-settings use cases.
- `application.port.out`: repositories, transactions, passwords, tokens, and account mail.
- `application.service`: framework-independent orchestration and version checks.
- `adapter.in.rest`: JSON DTOs, mapping, request validation, and error responses.
- `adapter.out`: JDBC persistence/transactions, JWT/PBKDF2 security, and SMTP mail.
- `adapter.in.security`: validated JWT-to-account/session identity mapping.
- `configuration`: CDI producers wiring application services to their ports.

Adapters depend inward on application/domain contracts. Lombok is compile-time only,
configured using its [Maven setup guide](https://projectlombok.org/setup/maven).
JDBC transactions and migration setup follow the Quarkus
[transaction](https://quarkus.io/guides/transaction/) and
[Flyway](https://quarkus.io/guides/flyway/) guides.

## Domain rules

- Customer codes are nonblank, trimmed, and case-sensitive. The application checks
  active status when a code is supplied. New codes are created with the time entry;
  omitted, null, or blank codes mean no customer assignment.
- Time entries have a stable client-supplied UUID, date, local start/end times,
  deducted minutes, optional customer code, and optional comment. Edits retain the UUID.
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

The packaged run requires production JWT/mail settings as described above.
Failsafe is configured for `*IT` tests, but no packaged integration tests currently
exist; `verify -DskipITs=false` does not itself verify a deployed authentication flow.

Keep the entire `target/quarkus-app/` directory when running the packaged application.
Domain/application tests use plain JUnit and fake output ports. REST and persistence
tests use Quarkus Dev Services with an isolated PostgreSQL 17 container (Docker/Colima
must be running). They do not use the local `timereport` database by default. Avoid
setting `QUARKUS_DATASOURCE_*` overrides when running tests. Coverage includes
authentication, refresh rotation/replay, session revocation, account isolation,
optional/inline customer codes, retries, conflicts, rollback, and database constraints.

Testcontainers uses standard Docker providers; no classes from another application
are required. If startup reports a missing Docker provider class from an unrelated
project, inspect `~/.testcontainers.properties` and remove the obsolete
`docker.client.strategy` entry. This user-wide cache is read by every repository;
adding the missing application as a dependency would not address the configuration
problem. See the [Testcontainers configuration documentation](https://java.testcontainers.org/features/configuration/).

Run just the authentication API tests with Docker available:

```shell
./mvnw test -Dtest=AuthenticationApiTest
```

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
