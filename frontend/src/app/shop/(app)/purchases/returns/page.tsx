"use client";

import { Plus } from "lucide-react";
import { type Column, ListPage } from "@/components/list-page";
import { LinkButton } from "@/components/ui/button";
import type { Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

type Row = Schemas["PurchaseReturnView"];

export default function PurchaseReturnsPage() {
  const can = useCan();
  const toHref = useShopHref();
  const columns: Column<Row>[] = [
    { label: "Return", render: (r) => <span className="font-mono font-medium">{r.number}</span> },
    { label: "Date", render: (r) => <span className="text-muted">{date(r.date)}</span> },
    { label: "Supplier", render: (r) => r.supplierName },
    { label: "Bill", render: (r) => <span className="font-mono text-muted">{r.grnNumber ?? "—"}</span> },
    { label: "Reason", render: (r) => label(r.reason) },
    { label: "Value", render: (r) => money(r.total, false), align: "right" },
  ];
  return (
    <ListPage<Row>
      title="Purchase returns"
      description="Goods sent back to suppliers. The value comes off what you owe them."
      path="/purchase-returns"
      queryKey="purchase-returns"
      columns={columns}
      actions={
        can(P.PURCHASING_MANAGE) && (
          <LinkButton href={toHref("/purchases/returns/new")}>
            <Plus /> New return
          </LinkButton>
        )
      }
      rowHref={(r) => `/purchases/returns/${r.id}`}
      empty={{ title: "No purchase returns" }}
    />
  );
}
