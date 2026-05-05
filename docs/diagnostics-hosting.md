# Diagnostics Hosting Runbook

Maintainer-facing setup guide for the two diagnostics backends.

## GlitchTip on Railway

GlitchTip speaks the Sentry wire protocol — Kofipod's `sentry-kotlin-multiplatform` SDK points at the GlitchTip DSN with no other changes.

### Provisioning

1. Create a new Railway project, name it `kofipod-glitchtip`.
2. Deploy from GlitchTip's official `docker-compose.yml`
   (https://glitchtip.com/documentation/install). Services:
   `glitchtip` (web), `worker`, `postgres`, `redis`.
3. Set environment variables on the `glitchtip` and `worker` services:
   - `SECRET_KEY` — generate with `openssl rand -hex 32`
   - `DATABASE_URL` — Railway-provided Postgres URL
   - `REDIS_URL` — Railway-provided Redis URL
   - `EMAIL_URL` — leave unset (no SMTP needed; admin signup is local)
   - `DEFAULT_FROM_EMAIL` — your contact email
   - `ENABLE_USER_REGISTRATION` — `true` initially, flip to `false`
     after creating the first admin account
   - `GLITCHTIP_MAX_EVENT_LIFE_DAYS` — `30` (or current name; check
     GlitchTip docs for the latest retention env var)
4. Attach a persistent volume to Postgres.
5. Custom domain: route a subdomain (e.g. `crash.kofipod.app`) at the
   `glitchtip` service. Railway provisions TLS automatically.
6. Sign up the admin account via the web UI; flip
   `ENABLE_USER_REGISTRATION=false`.
7. Create a project named `kofipod-android`. Copy the DSN (format:
   `https://<public_key>@crash.kofipod.app/<project_id>`).
8. Generate a personal auth token (Profile → Auth Tokens) with
   `project:write` scope. Use only in CI.

### Wiring into builds

- **Local dev**: paste DSN into `local.properties` as `SENTRY_DSN=...`.
- **CI**: set both `SENTRY_DSN` and `SENTRY_AUTH_TOKEN` as repo secrets.
  The Sentry Gradle plugin reads `SENTRY_AUTH_TOKEN` from the
  environment and uploads R8 mapping files on `assembleRelease` when
  both are present.

### Backups

Schedule a weekly Postgres dump via Railway's scheduled jobs feature.
Crash data is non-precious; losing a week is acceptable.

### Cost

Expect $5–8/month at idle for low-thousand-MAU traffic.

## Aptabase cloud

1. Sign up at https://aptabase.com.
2. Create an app named `Kofipod`. Copy the App Key (format `A-EU-…`
   or `A-US-…`).
3. **Local dev**: paste App Key into `local.properties` as
   `APTABASE_APP_KEY=...`.
4. **CI**: set as repo secret.

Free tier: 20 000 events/month with no overage charges (paused until
the next month if exceeded). At ~5 events/MAU/day this covers roughly
130 MAU. If usage outgrows the free tier, migrate to self-host using
the AGPL-3 server (https://github.com/aptabase/aptabase) on Railway —
the SDK side stays unchanged, only the `host` URL passed to
`Aptabase.instance.initialize` changes (currently null = cloud).
