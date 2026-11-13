"use client";

import { Plus } from "lucide-react";
import * as React from "react";
import { type Column, ListPage } from "@/components/list-page";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/layout";
import { PartyDialog } from "@/features/parties/party-form";
import type { Schemas } from "@/lib/api/client";
import { label, money } from "@/lib/format";
import { P, useCan, useShopRouter } from "@/lib/shop-context";

type Row = Schemas["CustomerListItem"];

export default function CustomersPage() {
  const can = useCan();
  const router = useShopRouter();
  const [adding, setAdding] = React.useState(false);
  const columns: Column<Row>[] = [
    {
      label: "Customer",
      render: (c) => (
        <>
          <span className="font-medium text-ink">{c.name}</span>
          <span className="block text-xs text-faint">{c.code}</span>
        </>
      ),
    },
    { label: "Phone", render: (c) => <span className="text-muted">{c.phone ?? "—"}</span> },
    { label: "Type", render: (c) => <span className="text-muted">{label(c.kind)}</span> },
    { label: "Credit limit", render: (c) => (c.creditLimit ? money(c.creditLimit, false) : "—"), align: "right" },
    {
      label: "Owes",
      align: "right",
      render: (c) =>
        c.balance === "0.00" ? "—" : (
          <span className={c.overLimit ? "text-bad" : c.balance.startsWith("-") ? "text-good" : "text-warn"}>
            {money(c.balance, false)} {c.overLimit && <Badge tone="bad">Over limit</Badge>}
          </span>
        ),
    },
    { label: "Status", render: (c) => (c.active ? null : <Badge tone="bad">Off</Badge>) },
  ];
  return (
    <>
      <ListPage<Row>
        title="Customers"
        description="Accounts, credit and statements."
        path="/customers"
        queryKey="customers"
        columns={columns}
        actions={
          can(P.CUSTOMERS_MANAGE) && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> New customer
            </Button>
          )
        }
        filters={[
          { kind: "search", placeholder: "Name, phone or code" },
          { kind: "check", param: "withBalance", label: "Who owe money" },
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
        rowHref={(c) => `/customers/${c.id}`}
        empty={{ title: "No customers yet", description: "Customers are added here or at the till with their phone number." }}
      />
      {adding && <PartyDialog kind="customer" onClose={() => setAdding(false)} onSaved={(id) => router.push(`/customers/${id}`)} />}
    </>
  );
}
