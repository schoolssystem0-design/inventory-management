"use client";

import { Plus } from "lucide-react";
import * as React from "react";
import { type Column, ListPage } from "@/components/list-page";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/layout";
import { PartyDialog } from "@/features/parties/party-form";
import type { Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { P, useCan, useShopRouter } from "@/lib/shop-context";

type Row = Schemas["SupplierListItem"];

export default function SuppliersPage() {
  const can = useCan();
  const router = useShopRouter();
  const [adding, setAdding] = React.useState(false);
  const columns: Column<Row>[] = [
    {
      label: "Supplier",
      render: (s) => (
        <>
          <span className="font-medium text-ink">{s.name}</span>
          <span className="block text-xs text-faint">
            {s.code}
            {s.company ? ` · ${s.company}` : ""}
          </span>
        </>
      ),
    },
    { label: "Phone", render: (s) => <span className="text-muted">{s.phone ?? "—"}</span> },
    { label: "Terms", render: (s) => (s.termsDays ? `${s.termsDays} days` : "Cash"), className: "text-muted" },
    {
      label: "You owe",
      align: "right",
      render: (s) => (s.balance === "0.00" ? "—" : <span className={s.balance.startsWith("-") ? "text-good" : "text-warn"}>{money(s.balance, false)}</span>),
    },
    { label: "Status", render: (s) => (s.active ? null : <Badge tone="bad">Off</Badge>) },
  ];
  return (
    <>
      <ListPage<Row>
        title="Suppliers"
        description="Distributors and companies you buy from, and what you owe them."
        path="/suppliers"
        queryKey="suppliers"
        columns={columns}
        actions={
          can(P.PURCHASING_MANAGE) && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> New supplier
            </Button>
          )
        }
        filters={[
          { kind: "search", placeholder: "Name, company, phone or code" },
          { kind: "check", param: "withBalance", label: "You owe money" },
          {
            kind: "select",
            param: "active",
            label: "Status",
            initial: "true",
            options: [
              { value: "true", label: "Active" },
              { value: "false", label: "Switched off" },
            ],
          },
        ]}
        rowHref={(s) => `/suppliers/${s.id}`}
        empty={{ title: "No suppliers yet" }}
      />
      {adding && <PartyDialog kind="supplier" onClose={() => setAdding(false)} onSaved={(id) => router.push(`/suppliers/${id}`)} />}
    </>
  );
}
