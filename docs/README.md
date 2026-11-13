# SelectCode POS — Project Documents

SelectCode POS is a multi-shop inventory and point-of-sale platform. Our super admin creates a shop for each client and picks its **shop type** (mobile shop, super store, medical store and 16 others). The type decides which modules, fields and rules that shop gets. It is built on the same stack as the School Management System.

## Source documents (the "why" and "what")

| File | What it is |
|---|---|
| [Multi-Shop_Inventory_Platform_Requirements.pdf](Multi-Shop_Inventory_Platform_Requirements.pdf) | Every functional requirement: roles, platform flow, super admin portal, 19 shop types, core and extra modules, integrations, reports, plans, acceptance checks, phases and decisions |
| [Inventory Management System — Stack and Deployment.pdf](Inventory%20Management%20System%20%E2%80%94%20Stack%20and%20Deployment.pdf) | The locked stack: Next.js + TypeScript on Vercel; Java 21 + Spring Boot and PostgreSQL on Railway; Cloudflare R2 |

Requirements are about what the system does and the stack PDF is about how it's built. When the Markdown files below and the PDFs disagree, the PDFs win. Fix the Markdown in the same pull request.

## Implementation documents (the "how")

Read them in this order.

| # | File | Who needs it | What it covers |
|---|---|---|---|
| 1 | [TECH-STACK.md](TECH-STACK.md) | Everyone | Exact versions, libraries and why each one is used |
| 2 | [ARCHITECTURE.md](ARCHITECTURE.md) | Developers | Repository layout, backend modules, tenancy, auth, module switches, stock engine, frontend structure |
| 3 | [SHOP-TYPES-AND-MODULES.md](SHOP-TYPES-AND-MODULES.md) | Developers, QA | Module keys, defaults per shop type, how a switch is enforced, type-specific fields |
| 4 | [DATA-MODEL.md](DATA-MODEL.md) | Backend developers | Tables per module, key columns, constraints and the rules the data must always keep |
| 5 | [API-CONVENTIONS.md](API-CONVENTIONS.md) | Backend and frontend developers | URL layout, errors, paging, money format, idempotency, endpoint list per module |
| 6 | [REQUIREMENTS-REGISTER.md](REQUIREMENTS-REGISTER.md) | Everyone | Every requirement with an ID (e.g. `POS-03`), its phase and the PDF section it comes from |
| 7 | [IMPLEMENTATION-STAGES.md](IMPLEMENTATION-STAGES.md) | Everyone | **The build order we follow:** six stages (Backend A, Frontend A, Backend B, Frontend B, Connect, QA), each ending with a report and approval |
| 7a | [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) | Developers, project lead | Work packages in detail, porting map, definition of done, risks |
| 8 | [QA-TEST-PLAN.md](QA-TEST-PLAN.md) | QA, developers | Test strategy, environments, demo data, test cases per module, release gates, bug severity |
| 9 | [SECURITY.md](SECURITY.md) | Developers, reviewers | Security controls checklist and review rules |
| 10 | [CODING-STANDARDS.md](CODING-STANDARDS.md) | Developers | Code rules, git workflow, pull request checklist, UI design tokens |
| 11 | [DEPLOYMENT-RUNBOOK.md](DEPLOYMENT-RUNBOOK.md) | DevOps, project lead | Environments, environment variables, first deploy, releases, backups, restore, monitoring, incidents |
| — | [test-vectors/money.json](test-vectors/money.json) | Developers, QA | Shared numbers (average cost, discount spread, return refund, reorder, weighed items) used by both Java and TypeScript tests |

## The current build

The first working version (Next.js 15 in JavaScript on Neon, single shop) was removed from this folder on 2026-11-08 so the platform can be built from scratch. A copy is kept at `D:\inventory-management-system-old-build.zip`. Its business rules are written down in IMPLEMENTATION-PLAN.md §3 and as numbers in [test-vectors/money.json](test-vectors/money.json). Those are the reference for behaviour. Open the zip only to check a detail. Wherever these documents say "the current build", they mean that archived version.

## Glossary

| Term | Meaning |
|---|---|
| Platform | The whole SelectCode POS system, run by SelectCode |
| Super admin | Top-level platform login that manages every shop |
| Shop (tenant) | One client business; all its data carries its `shop_id` |
| Branch | A location inside a shop that holds stock (shop floor, godown, branch 2). Called "warehouse" in the current build |
| Shop type | Template chosen at creation (Mobile shop, Super store, Medical store …) that sets default modules |
| Module | A feature group that is on or off per shop, e.g. `imei`, `batches`, `variants` |
| Plan | Subscription level (Starter, Business, Enterprise) that sets limits |
| Stock movement | One row in the stock ledger: product, branch, quantity in or out, balance after, document |
| GRN | Goods received note: receiving stock from a supplier |
| FEFO | First expiry, first out: sell the batch that expires first |
| Tier-1 retailer | A retailer that must report every sale to FBR in real time |
