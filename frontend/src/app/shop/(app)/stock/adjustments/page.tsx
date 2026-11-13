"use client";

import { Plus } from "lucide-react";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { LinkButton } from "@/components/ui/button";
import type { Schemas } from "@/lib/api/client";
import { dateTime, label, money } from "@/lib/format";
import { ADJUST_REASONS } from "@/features/documents/reasons";
import { P, useCan, useShop, useShopHref } from "@/lib/shop-context";

type Row = Schemas["AdjustmentListItem"];


export default function AdjustmentsPage() {
  const can = useCan();
  const shop = useShop();
  const toHref = useShopHref();
  const columns: Column<Row>[] = [
    { label: "Number", render: (a) => <span className="font-mono font-medium">{a.number}</span> },
    { label: "When", render: (a) => <span className="text-muted">{dateTime(a.at)}</span> },
    { label: "Reason", render: (a) => label(a.reason) },
    { label: "Branch", render: (a) => <span className="text-muted">{a.branchName}</span>, hidden: shop.branches.length < 2 },
    { label: "Lines", render: (a) => a.lines, align: "right" },
    { label: "Value out", render: (a) => (a.valueOut !== "0.00" ? <span className="text-warn">{money(a.valueOut, false)}</span> : "—"), align: "right", hidden: !can(P.COST_VIEW) },
    { label: "Value in", render: (a) => (a.valueIn !== "0.00" ? <span className="text-good">{money(a.valueIn, false)}</span> : "—"), align: "right", hidden: !can(P.COST_VIEW) },
    { label: "By", render: (a) => <span className="text-muted">{a.actorName}</span> },
  ];
  return (
    <ListPage<Row>
      title="Stock adjustments"
      description="Damaged, expired, lost, found or used in the shop. Large write-offs need a manager's PIN."
      path="/adjustments"
      queryKey="adjustments"
      columns={columns}
      actions={
        can(P.STOCK_ADJUST) && (
          <LinkButton href={toHref("/stock/adjustments/new")}>
            <Plus /> New adjustment
          </LinkButton>
        )
      }
      filters={[
        { kind: "dates" },
        ...branchFilter(shop.branches),
        { kind: "select", param: "reason", label: "Reason", options: [{ value: "", label: "All reasons" }, ...ADJUST_REASONS.map((r) => ({ value: r.value, label: r.label }))] },
      ]}
      rowHref={(a) => `/stock/adjustments/${a.id}`}
      empty={{ title: "No adjustments" }}
    />
  );
}
