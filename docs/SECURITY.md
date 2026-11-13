# Security

SelectCode POS holds the money, stock, customers and CNIC numbers of many businesses in one database. A leak between shops is the worst failure we can have. This file is the checklist every design and pull request is reviewed against.

## 1. What we protect and from whom

| Asset | Threat | Main controls |
|---|---|---|
| Each shop's business data | Another shop's user, a curious employee, a bug in a query | `@TenantId` + RLS + isolation tests; branch scoping; roles |
| Cost, profit, supplier prices | Cashiers and salesmen | `cost.view` permission; serializer test |
| Customer personal data (phone, CNIC, photos, prescriptions) | Leaks, over-sharing on prints | Role-based visibility, masking, private R2, short-lived URLs |
| Money and stock records | Fraud by staff (fake returns, discounts, deletions) | Approvals with PIN, immutable documents, audit log, till sessions, write-off limits |
| Platform control (suspend, delete, support access) | Stolen platform password | TOTP, short sessions, audit, IP alerts on new location |
| Availability | Abuse, heavy exports, outages | Rate limits, background jobs, monitoring, backups |

## 2. Controls checklist

### Tenancy
- [ ] Every shop table has `shop_id`, an RLS policy and `@TenantId` on its entity
- [ ] The app connects with a database role that cannot bypass RLS; migrations use a separate owner role
- [ ] `shop_id` is never accepted from the client; it comes from the session
- [ ] Native SQL and report queries include `shop_id` explicitly **and** still run under RLS
- [ ] Cross-shop queries only in `platform`, only under `TenantContext.allShops()`, only counts/totals
- [ ] New endpoint → covered by `TenantIsolationIT` automatically (check it appears in the run)
- [ ] Files stored under `shops/{shopId}/...` and served only after a tenant and permission check

### Authentication
- [ ] Passwords hashed with bcrypt (cost 12) or Argon2id; minimum 8 characters; common passwords refused
- [ ] Passwords are never displayed, logged or emailed; resets by one-time token (hashed in DB, 48 h for setup, 1 h for reset)
- [ ] Lock after 5 failures for 15 min; login events stored
- [ ] Platform users must use TOTP; owners may
- [ ] Session cookie `HttpOnly; Secure; SameSite=Lax`; session id rotated at login; idle timeout; "sign out everywhere"
- [ ] PIN for approvals: 4–6 digits, hashed, locked after 5 failures, separate from password
- [ ] Bootstrap super admin from environment variables only on first start; variables removed after first sign-in

### Authorisation
- [ ] Every controller method has a permission check (`@PreAuthorize`) or is explicitly public
- [ ] Switchable module controllers carry `@RequiresModule`
- [ ] Read-only and suspended shops blocked in a filter before controllers
- [ ] Support sessions are read-only unless the owner approved writes; flagged in audit
- [ ] Approval limits checked on the server, not only in the UI

### Input and output
- [ ] Bean Validation on every request record; size limits on strings and lists
- [ ] Uploads: type checked by content (not extension), max 10 MB, images re-encoded, no SVG uploads
- [ ] Excel import parsed with POI in a streaming reader; formulas ignored; row limit 50,000
- [ ] No string-built SQL; JPA or named parameters only
- [ ] Error responses never include stack traces or SQL (`include-stacktrace: never`)
- [ ] CSV/Excel exports escape cells starting with `=`, `+`, `-`, `@` (formula injection)
- [ ] React escapes output; no `dangerouslySetInnerHTML` except sanitised receipt templates

### Transport and browser
- [ ] HTTPS only; HSTS on all domains
- [ ] CORS limited to our domains and verified custom domains
- [ ] CSRF token on all state-changing requests
- [ ] Security headers: CSP (self + our CDN), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'` (print pages included)

### Secrets and infrastructure
- [ ] Secrets only in Railway/Vercel environment variables; `.env*` in `.gitignore`; secret scanning on GitHub
- [ ] Database reachable only over Railway's private network
- [ ] R2 bucket private; access keys scoped to the bucket; separate keys for backups
- [ ] Different secrets for staging and production; production data never copied to staging without masking

### Audit and monitoring
- [ ] Audit row for every create, update, cancel, approval, setting change, module switch, support session
- [ ] Audit tables append-only (no update/delete grants for the app role)
- [ ] Alerts: many failed logins on one account or IP, platform login from a new location, restore or delete actions, error spikes
- [ ] Logs carry shop id and user id, never passwords, PINs, tokens, full CNIC or card data

### Data protection
- [ ] CNIC shown masked (`35202-*****-1`) except to roles with `customer.pii`
- [ ] Prints mask phone/CNIC by default (setting)
- [ ] Closed shops deleted after 90 days, including R2 files; backups age out after 30 days
- [ ] Owner can export all their data at any time

## 3. Pull request security review

A reviewer answers these for every pull request that touches the backend:

1. Does any new query or table bypass `shop_id`/RLS?
2. Does any new response include cost, profit or personal data? Is it gated?
3. Is every new endpoint permission-checked and module-checked?
4. Can this action be done twice by a double-click or retry (idempotency)?
5. Is anything that changes money or stock done outside one transaction or outside `StockService`?
6. Is the action audited?

## 4. Incidents

- Suspected data leak or breach: the project lead is told at once. Affected endpoints are switched off (feature flag) and logs preserved. Affected shop owners are told within 72 hours with what happened and what we did.
- Lost platform credential: revoke sessions, reset password and TOTP, review the audit log for that user.
- Steps for operations incidents are in DEPLOYMENT-RUNBOOK.md §9.
