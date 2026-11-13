# Deployment Runbook

How SelectCode POS is hosted, released, backed up and restored. It follows the stack PDF and the School Management System's setup on Vercel and Railway.

## 1. Environments

| | Staging | Production |
|---|---|---|
| Frontend (Vercel) | `staging.selectcodepos.com`, `*.staging.selectcodepos.com` | `selectcodepos.com`, `*.selectcodepos.com`, `admin.selectcodepos.com`, verified custom domains |
| Backend (Railway) | `api-staging.selectcodepos.com` | `api.selectcodepos.com` |
| Database | Railway Postgres (staging environment), demo data only | Railway Postgres (production environment) |
| R2 buckets | `scpos-staging-files`, `scpos-staging-backups` | `scpos-files`, `scpos-backups` |
| Region | Railway: the region closest to Pakistan (Singapore, confirm on the plan); Vercel functions `sin1` | Same |
| Deploys from | `main` (automatic) | Git tags `v*` (manual promote) |

Wildcard DNS (`*.selectcodepos.com`) points to Vercel so every new shop works without DNS changes. Vercel needs the wildcard domain added with Vercel's nameservers or a DNS-01 verified certificate.

## 2. Accounts and access

- GitHub organisation `selectcode`: repository `selectcode-pos`; only leads can push tags.
- Vercel team project `selectcode-pos-frontend`, Railway project `selectcode-pos` (separate from the school system, as decided), Cloudflare account for DNS and R2.
- Two people hold owner access to every account. Two-step login on all of them.
- Spending limit set on Railway; usage reviewed on the 1st of each month.

## 3. Railway services

| Service | Source | Notes |
|---|---|---|
| `api` | `backend/Dockerfile` | Health check `/actuator/health`; 2 replicas in production; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` to fit the plan's memory |
| `postgres` | Railway Postgres | Private networking only; Railway backups on |
| `db-backup` | Cron service (`0 21 * * *` UTC = 02:00 PKT) | `pg_dump -Fc` → gzip → upload to R2 `backups/YYYY/MM/DD/` → delete objects older than 30 days → report to health channel |
| `sms-gateway` (optional) | As in the school system | Talks to the Android gateway phone(s) |

## 4. Environment variables

### Backend (`api`)

| Name | Example / note |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `staging` or `prod` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}` (private host) |
| `DB_APP_USER`, `DB_APP_PASSWORD` | Restricted login role (subject to RLS) |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | Owner role used by Flyway |
| `APP_WEB_BASE_URL` | `https://selectcodepos.com` |
| `APP_ALLOWED_ORIGINS` | `https://*.selectcodepos.com` |
| `APP_SESSION_IDLE_MINUTES` | `10` (shop default) |
| `BOOTSTRAP_SUPERADMIN_EMAIL`, `_PASSWORD`, `_NAME` | First start only; **remove after the first sign-in** |
| `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | Files bucket keys (not the backup keys) |
| `SPRING_MAIL_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` or `MAIL_PROVIDER` + `MAIL_API_KEY` | Email |
| `MAIL_FROM`, `MAIL_FROM_NAME` | `no-reply@selectcodepos.com`, `SelectCode POS` |
| `SMS_GATEWAY_URL`, `SMS_GATEWAY_TOKEN` | Android gateway |
| `SENTRY_DSN` | Error tracking |
| `FBR_*` | Phase 2 |

### Frontend (Vercel)

| Name | Example |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.selectcodepos.com` |
| `NEXT_PUBLIC_ROOT_DOMAIN` | `selectcodepos.com` |
| `NEXT_PUBLIC_SENTRY_DSN` | Error tracking |

### Backup service (`db-backup`)

`PGHOST`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`, `R2_BACKUP_ENDPOINT`, `R2_BACKUP_ACCESS_KEY_ID`, `R2_BACKUP_SECRET_ACCESS_KEY`, `R2_BACKUP_BUCKET`.

Secrets live only in Railway/Vercel. Staging and production never share a secret.

## 5. First deployment (once per environment)

1. Create the Railway project, add Postgres, enable private networking and backups.
2. The restricted app role is created on first migration by a Flyway callback ported from the school system (`shared/persistence/AppDatabaseRoleCallback`), using `DB_APP_USER` / `DB_APP_PASSWORD`.
3. Deploy `api` with the variables above, including the `BOOTSTRAP_SUPERADMIN_*` values. Check `/actuator/health` is `UP` and Flyway reports all migrations applied.
4. Create the R2 buckets (private) and the two key pairs (files and backups).
5. Deploy the frontend on Vercel; add `selectcodepos.com`, `*.selectcodepos.com` and `admin.selectcodepos.com`.
6. Sign in at `admin.` as the bootstrap super admin, enrol TOTP, then **remove the `BOOTSTRAP_SUPERADMIN_*` variables** and redeploy.
7. Create the demo shops (one per type) from the portal or the seeder job.
8. Add the `db-backup` cron service; run it once by hand and check the file appears in R2.
9. Set up uptime monitors (frontend, `api` health, one demo shop login page) and Sentry alerts.

## 6. Releasing

1. Merge to `main` → CI → automatic deploy to staging (Flyway migrates staging).
2. QA runs the release checks (QA-TEST-PLAN.md §4, §8) on staging.
3. Lead tags `vX.Y.Z` with release notes (requirement IDs, migrations, known issues).
4. Production deploy runs **after 1 am Pakistan time**. Railway deploys the tagged image; Flyway runs before the new version takes traffic; Vercel promotes the matching frontend build.
5. Smoke test on the production demo shop within 15 minutes: login, sell, return, report, print.
6. Post "What's new" to shops if the release has visible changes.

**Migrations must be backward compatible** with the previous backend version (add columns nullable or with defaults; never drop or rename in the same release), so a rollback of the app never needs a database rollback.

## 7. Backups and restore

| Backup | Frequency | Kept | Where |
|---|---|---|---|
| Railway Postgres backups | Daily (Railway) | Per plan | Railway |
| Logical dump (`pg_dump -Fc`) | Daily 02:00 PKT | 30 days | R2 `scpos-backups` |
| Owner Excel backup | On demand | Owner's own copy | Download |
| R2 files | Versioning on the files bucket | 30 days of old versions | R2 |

**Full restore (disaster):** create a new Railway Postgres → `pg_restore` the latest dump → point `api` at it → run Flyway (no-op if at the same version) → smoke test → switch traffic. Target: back online within 4 hours, losing at most 24 hours of data. Use Railway's own backups first if they are newer.

**Single-shop restore (Phase 2 tool):** restore the dump into a temporary database → export that shop's rows by `shop_id` → in a maintenance window, delete the shop's current rows and insert the restored rows in one transaction → run `StockAudit` for the shop → tell the owner.

**Restore test:** on the 1st of every month, restore last night's dump into a scratch database and compare row counts per table with production (QA TC-BKP-01). Record the result in the ops log.

## 8. Monitoring and alerts

| Signal | Tool | Alert when |
|---|---|---|
| Uptime | Uptime monitor (1-minute checks) | Down 2 checks in a row |
| Errors | Sentry | New issue or > 20 events/10 min |
| API latency | Actuator metrics / Railway | p95 > 1 s for 10 min |
| Database | Railway metrics | CPU > 80% or disk > 80% |
| Backups | `db-backup` report | No successful dump by 03:00 PKT |
| Stock/balance audit | Nightly job | Any difference found |
| Security | App events | Platform login from a new location; many failed logins; shop delete/restore |
| FBR (Phase 2) | Sync job | Invoices unsent for more than 30 min |

Alerts go to the team WhatsApp group and email. The person on duty acknowledges within 30 minutes in shop hours (10 am – 11 pm PKT).

## 9. Incidents

| Situation | First steps |
|---|---|
| Site down | Check Railway/Vercel status pages and service health; roll back to the previous deploy if it started after a release |
| Slow POS | Check API latency and database CPU; look for a heavy report or export; scale `api` replicas |
| Wrong stock for a shop | Run `StockAudit` for the shop; find the movement that differs; fix with an adjustment carrying a support note; find and fix the code path |
| Suspected data leak | Follow SECURITY.md §4 |
| Bad release | Roll back the app (Railway "redeploy previous", Vercel "promote previous"); the database stays (migrations are backward compatible) |

After every incident: a short written note (what happened, impact, cause, fix, prevention) in the ops log within 2 days.

## 10. Supported hardware (for onboarding)

| Device | Tested models (keep this list updated) |
|---|---|
| Receipt printers | 80 mm and 58 mm USB thermal printers that print from Chrome (e.g. Black Copper, Xprinter, Epson TM series) |
| Label printers | Zebra, TSC, Xprinter label printers; A4 sticker sheets on any laser printer |
| Scanners | Any USB/Bluetooth scanner in keyboard mode (1D; 2D for QR) |
| Cash drawer | RJ11 drawer kicked by the receipt printer |
| Devices | Windows 10/11 with Chrome or Edge; Android 10+ with Chrome; iPhone with Safari |

The model names are examples to start from. QA confirms each model before it goes on the list.
