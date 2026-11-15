# Tirvex Tech POS

Multi-shop inventory and point-of-sale platform. The super admin creates shops for clients and picks each shop's
type (mobile shop, super store, medical store, fast food and 7 more), which decides the features it gets.

## Folders

```
inventory-management/      (this repository — monorepo)
├── backend/                 Java 21 + Spring Boot API (all business rules and data)
│   ├── src/main/java/com/selectcode/pos/
│   │   ├── platform/        super admin: shops, plans, billing, demo shops
│   │   ├── iam/             users, roles, sign-in, PIN approvals
│   │   ├── shop/            shop settings, branches, tills, numbering
│   │   ├── catalog/         products, units, barcodes, variants, import
│   │   ├── stock/           stock movements, counts, transfers, alerts
│   │   ├── purchasing/      suppliers, purchase orders, goods received
│   │   ├── sales/           till, sales, returns, offers, loyalty, repairs,
│   │   │                    installments, prescriptions, deliveries, kitchen
│   │   ├── customers/       customers and their accounts
│   │   ├── cash/            expenses, daily cash, profit and loss
│   │   ├── reports/         reports, dashboard, exports
│   │   ├── notifications/   email, SMS, in-app messages
│   │   └── shared/          security, tenancy, money, files, web helpers
│   ├── src/main/resources/  settings and database migrations (db/migration)
│   ├── src/test/java/       unit, integration, security and architecture tests
│   ├── openapi.json         the API description the frontend types are generated from
│   └── pom.xml
├── frontend/                Next.js 16 + TypeScript web app (screens only, talks to the API)
│   ├── src/app/             pages: shop/ (shop app), platform/ (super admin), landing/
│   ├── src/components/      shared UI: layout, fields, dialogs, shell and menus
│   ├── src/features/        bigger screen parts: pos/, receipts/, documents/, parties/, catalog/
│   ├── src/lib/             API client, money maths, formatting, shop context
│   ├── tests/e2e/           Playwright browser tests
│   ├── scripts/             screenshot scripts for the stage reports
│   └── package.json
├── docs/                    requirements, architecture, plans, stage reports, shared test numbers
│   └── README.md            start here
└── .github/workflows/       CI: backend.yml and frontend.yml
```

## Run on this PC

**Backend** (no database to install; it starts its own PostgreSQL and six demo shops):

```bash
cd backend
./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"
```

**Frontend** (with the backend running):

```bash
cd frontend
npm install
npm run dev
```

- Shops (user `owner`, password printed by the backend): http://demo-mobile.localhost:3000, http://demo-mart.localhost:3000,
  http://demo-pharmacy.localhost:3000, http://demo-electronics.localhost:3000, http://demo-hardware.localhost:3000,
  http://demo-fastfood.localhost:3000
- Super admin portal: http://admin.localhost:3000 (TOTP secret printed by the backend; add it to an authenticator app)

`*.localhost` works in Chrome, Edge and Firefox with no hosts-file changes.

## Tests

```bash
cd backend
./mvnw verify      # unit + integration tests, security and permission checks, architecture rules

cd frontend
npm run lint && npm run typecheck && npm test   # lint, types, unit and component tests
npm run build && npm run e2e                    # browser tests (needs the backend running)
```

## GitHub and production

| Repo | Purpose | URL |
|------|---------|-----|
| [inventory-management](https://github.com/schoolssystem0-design/inventory-management) | Monorepo (source of truth for local work) | This folder |
| [inventory-pos-frontend](https://github.com/schoolssystem0-design/inventory-pos-frontend) | Vercel deploy | [inventory-pos-frontend.vercel.app](https://inventory-pos-frontend.vercel.app) |
| [inventory-pos-backend](https://github.com/schoolssystem0-design/inventory-pos-backend) | Railway deploy | API on Railway |

**Pushes to deploy:** copy changed paths into the `-publish` clones (or mirror workflow you use), then commit on `main` with author **`schoolssystem0-design <schoolssystem0@gmail.com>`** so Vercel accepts commits on the private frontend repo (Hobby plan).

**Local remotes:** monorepo → `inventory-management`; publish folders → `inventory-pos-frontend` / `inventory-pos-backend` (see [docs/planning/DEPLOYMENT-RUNBOOK.md](docs/planning/DEPLOYMENT-RUNBOOK.md) if present).

## Status

Stages 1–4, 4b (all modules of the 10 shop types) and 4c (fast food) are built and tested; a QA and security pass is done. Next is
Stage 5, deploying to staging. Build order and status: [docs/planning/IMPLEMENTATION-STAGES.md](docs/planning/IMPLEMENTATION-STAGES.md).
