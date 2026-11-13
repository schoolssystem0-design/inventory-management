"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import * as React from "react";
import { Suspense } from "react";
import { useCapability } from "@/components/shell/platform-shell";
import { LinkButton } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/fields";
import { Badge, Empty, PageHeader, Pager, Panel, Table, TableSkeleton, Td, Th, statusTone } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { date, label } from "@/lib/format";

/** PLT-07: every shop, searchable by name, code, owner or phone and filtered by type, plan, status and city. */
export default function ShopsPage() {
  return (
    <Suspense fallback={<TableSkeleton />}>
      <Shops />
    </Suspense>
  );
}

function Shops() {
  const cap = useCapability();
  const params = useSearchParams();
  const [q, setQ] = React.useState("");
  const [type, setType] = React.useState("");
  const [plan, setPlan] = React.useState("");
  const [status, setStatus] = React.useState(params.get("status") ?? "");
  const [page, setPage] = React.useState(0);
  const types = useQuery({ queryKey: ["shop-types"], queryFn: () => api<Schemas["ShopTypeView"][]>("/platform/shop-types") });
  const plans = useQuery({ queryKey: ["plans"], queryFn: () => api<Schemas["PlanView"][]>("/platform/plans") });
  const query = new URLSearchParams({ page: String(page), size: "50", q, type, plan, status });
  const shops = useQuery({
    queryKey: ["platform-shops", query.toString()],
    queryFn: () => api<Schemas["PageResponseShopListItem"]>(`/platform/shops?${query}`),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      <PageHeader
        title="Shops"
        description={shops.data ? `${shops.data.total} shops` : undefined}
        actions={
          cap("platform.shops.create") && (
            <LinkButton href="/shops/new">
              <Plus /> New shop
            </LinkButton>
          )
        }
      />
      <Panel padded={false}>
        <div className="flex flex-wrap gap-2 border-b border-line p-3">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Name, code, owner or phone" className="pl-9" aria-label="Search shops" />
          </div>
          <Select value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} className="w-auto" aria-label="Shop type">
            <option value="">All types</option>
            {types.data?.map((t) => (
              <option key={t.key} value={t.key}>
                {t.name}
              </option>
            ))}
          </Select>
          <Select value={plan} onChange={(e) => { setPlan(e.target.value); setPage(0); }} className="w-auto" aria-label="Plan">
            <option value="">All plans</option>
            {plans.data?.map((p) => (
              <option key={p.key} value={p.key}>
                {p.name}
              </option>
            ))}
          </Select>
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="w-auto" aria-label="Status">
            <option value="">All statuses</option>
            {["TRIAL", "ACTIVE", "GRACE", "READ_ONLY", "SUSPENDED", "CLOSED"].map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </Select>
        </div>
        {shops.isPending ? (
          <TableSkeleton />
        ) : (shops.data?.items.length ?? 0) === 0 ? (
          <Empty title="No shops found" />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Shop</Th>
                  <Th>Type</Th>
                  <Th>Plan</Th>
                  <Th>Owner</Th>
                  <Th>Next due</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {shops.data!.items.map((s) => (
                  <tr key={s.id} className="hover:bg-raised/40">
                    <Td>
                      <Link href={`/shops/${s.id}`} className="font-medium hover:text-aqua">
                        {s.name}
                      </Link>
                      <p className="font-mono text-xs text-faint">
                        {s.code}
                        {s.city ? ` · ${s.city}` : ""}
                      </p>
                    </Td>
                    <Td className="text-muted">{s.typeName}</Td>
                    <Td className="text-muted">{s.planName}</Td>
                    <Td className="text-muted">
                      {s.ownerName}
                      {s.phone && <p className="text-xs text-faint">{s.phone}</p>}
                    </Td>
                    <Td className="text-muted">{date(s.status === "TRIAL" ? s.trialEndsOn : s.nextDueOn)}</Td>
                    <Td>
                      <Badge tone={statusTone(s.status)}>{label(s.status)}</Badge>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pager page={page} size={50} total={shops.data!.total} onPage={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
