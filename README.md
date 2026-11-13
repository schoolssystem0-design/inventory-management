# SelectCode POS

Multi-shop inventory and point-of-sale platform. The super admin creates shops for clients and picks each shop's
type (mobile shop, super store, medical store and more), which decides the features it gets.

| Folder | What |
|---|---|
| `backend/` | Java 21 + Spring Boot 4.1 API (Stage 1 done) |
| `frontend/` | Next.js 16 + TypeScript app (Stage 2) |
| `docs/` | Requirements, architecture, plans, QA, runbook — start with [docs/README.md](docs/README.md) |

## Run the backend on this PC

No database to install: the API starts its own PostgreSQL.

```bash
cd backend
./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"
```

It prints the demo logins: shops `demo-mobile`, `demo-mart`, `demo-pharmacy` (user `owner`), and a super admin.

## Run the web app

With the backend running (above):

```bash
cd frontend
npm install
npm run dev
```

- Shops: http://demo-mobile.localhost:3000, http://demo-mart.localhost:3000, http://demo-pharmacy.localhost:3000
- Super admin portal: http://admin.localhost:3000 (TOTP secret printed by the backend; add it to an authenticator app)

`*.localhost` works in Chrome, Edge and Firefox with no hosts-file changes.

## Tests

```bash
cd backend
./mvnw verify      # unit + integration tests, architecture rules, shop data separation sweep

cd frontend
npm run lint && npm run typecheck && npm test   # lint, types, unit/component tests
npm run build && npm run e2e                    # end-to-end journeys (needs the backend running)
```

Build order and status: [docs/IMPLEMENTATION-STAGES.md](docs/IMPLEMENTATION-STAGES.md).
