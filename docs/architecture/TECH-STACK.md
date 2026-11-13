# Tech Stack

Locked by *Inventory Management System — Stack and Deployment* (PDF). Versions match the School Management System (`D:\new-school-system`) so both products share knowledge, code patterns and tooling. Upgrade both together.

## Summary

| Layer | Choice | Hosted on |
|---|---|---|
| Frontend | Next.js 16 + React 19 + TypeScript 5, Tailwind CSS 4, shadcn/ui on Base UI | Vercel, region `sin1` |
| Backend | Java 21 + Spring Boot 4.1 (Web MVC, Security, Data JPA, Validation, Session JDBC, Actuator, Mail), Spring Modulith | Railway (paid plan), Docker |
| Database | PostgreSQL 16+, Flyway migrations, row-level security | Railway, same project and region as the backend |
| Files and backups | Cloudflare R2 (S3 API) | Cloudflare |
| PDFs and Excel | OpenPDF 3 (invoices, POs, statements, registers); Apache POI 5 (import, export, backups) | Inside the backend |
| Receipts and labels | Browser printing (80 mm / 58 mm / A4); JsBarcode for labels | Frontend |
| Barcode scanning | USB/Bluetooth scanners as keyboard; camera via `barcode-detector` (native API with ZXing WASM fallback) | Frontend |

## Backend libraries

| Library | Use | Notes |
|---|---|---|
| `spring-boot-starter-webmvc` | REST API | Virtual threads on (`spring.threads.virtual.enabled: true`) |
| `spring-boot-starter-security` | Login, roles, method rules | Two login worlds: platform and shop (ARCHITECTURE.md §5) |
| `spring-boot-starter-session-jdbc` | Server-side sessions in Postgres | HTTP-only, Secure, SameSite=Lax cookie. No JWT in the browser |
| `spring-boot-starter-data-jpa` + Hibernate 7 | Persistence | `@TenantId` on tenant entities; `ddl-auto: validate` |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Migrations | `V###__description.sql`, add-only |
| `spring-boot-starter-validation` | Request validation | Bean Validation on request records |
| `spring-modulith-starter-core` | Module boundaries and events | One top-level package per module; cross-module calls through published APIs or events |
| `spring-boot-starter-actuator` | Health, metrics | `/actuator/health` for Railway health checks |
| `spring-boot-starter-mail` | Email | SMTP, or Brevo/Resend HTTPS API as in the school system |
| `springdoc-openapi-starter-webmvc-api` | OpenAPI document | Generates `openapi.json` at build; frontend types come from it |
| `openpdf` 3.x | PDF documents | Urdu text via embedded Noto Nastaliq Urdu font |
| `poi-ooxml` 5.x | Excel import/export | Streaming (SXSSF) for large exports |
| AWS SDK v2 `s3` | R2 storage | Private bucket; pre-signed URLs, 5-minute expiry |
| `postgresql` driver | JDBC | |

**Backend test libraries:** `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-starter-data-jpa-test`, `spring-modulith-starter-test`, `embedded-postgres` (real Postgres in tests, no H2), `archunit-junit5` (architecture rules), Maven Failsafe for `*IT` integration tests.

## Frontend libraries

| Library | Use |
|---|---|
| `next` 16, `react` 19, `typescript` 5 | App Router, server components for shells, client components for interactive screens |
| `tailwindcss` 4 + `@tailwindcss/turbopack` | Styling with the Mobile Shop design tokens (CODING-STANDARDS.md §6) |
| `shadcn` + `@base-ui/react`, `class-variance-authority`, `tw-animate-css` | Accessible components |
| `@tanstack/react-query` 5 | Server state, caching, background refresh, optimistic updates on the till |
| `zod` 4 | Form validation, mirroring backend rules |
| `openapi-typescript` | `npm run gen:api` creates `src/lib/api/schema.d.ts` from the backend's `openapi.json` |
| `lucide-react` | Icons |
| `sonner` | Toasts |
| `barcode-detector`, `jsbarcode` | Camera scanning, label barcodes (already used in the current build) |
| `qrcode` | QR on receipts (FBR, Raast, digital receipt link) |
| Fonts via `next/font` | Manrope (body), JetBrains Mono (numbers, codes), Noto Nastaliq Urdu (Urdu text) |

**Frontend test libraries:** `vitest` + `@testing-library/react` + `jsdom` (unit and component), `@playwright/test` (end-to-end), ESLint 9 with `eslint-config-next`, Prettier.

## Tooling

| Tool | Use |
|---|---|
| GitHub | One repository, `backend/` and `frontend/` folders, protected `main` |
| GitHub Actions | Build, tests, type check, lint, OpenAPI drift check on every pull request |
| Docker | Backend image for Railway (`backend/Dockerfile`, multi-stage, JRE 21) |
| Maven wrapper | `./mvnw verify` runs unit and integration tests |
| Sentry (or equivalent) | Error tracking for frontend and backend, personal data scrubbed |
| Uptime monitor | Pings frontend and `/actuator/health` every minute |

## Rules

- Exact versions are pinned in `pom.xml` and `package-lock.json`. Renovate/Dependabot pull requests are reviewed weekly.
- No new runtime dependency without a line in this file saying what it's for.
- Money is `BigDecimal` in Java, `numeric(14,2)` in Postgres and a **string** in JSON. Never use `double` or a JS `number` for money arithmetic (CODING-STANDARDS.md §3).
