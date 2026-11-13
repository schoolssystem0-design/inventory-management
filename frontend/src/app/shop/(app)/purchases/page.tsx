"use client";

import { PackagePlus, Plus } from "lucide-react";
import * as React from "react";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import { Tabs } from "@/components/ui/layout";
import type { Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { P, ShopLink, useCan, useShop, useShopHref } from "@/lib/shop-context";

export default function PurchasesPage() {
  const can = useCan();
  const shop = useShop();
  const toHref = useShopHref();
  const [tab, setTab] = React.useState<"orders" | "grns">("orders");
  const actions = can(P.PURCHASING_MANAGE) && (
    <>
      <LinkButton variant="secondary" href={toHref("/purchases/receive")}>
        <PackagePlus /> Receive goods
      </LinkButton>
      <LinkButton href={toHref("/purchases/new")}>
        <Plus /> New order
      </LinkButton>
    </>
  );
  const tabs = <Tabs value={tab} onChange={setTab} tabs={[{ value: "orders", label: "Purchase orders" }, { value: "grns", label: "Goods received (bills)" }]} />;
  if (tab === "grns") {
    const columns: Column<Schemas["GrnListItem"]>[] = [
      { label: "GRN", render: (g) => <span className="font-mono font-medium">{g.number}</span> },
      { label: "Date", render: (g) => <span className="text-muted">{date(g.date)}</span> },
      { label: "Supplier", render: (g) => g.supplierName },
      { label: "Supplier bill", render: (g) => <span className="text-muted">{g.supplierBillNo ?? "—"}</span> },
      { label: "Branch", render: (g) => <span className="text-muted">{g.branchName}</span>, hidden: shop.branches.length < 2 },
      { label: "Total", render: (g) => money(g.total, false), align: "right" },
      {
        label: "Due",
        align: "right",
        render: (g) => (g.due === "0.00" ? "—" : <span className="text-warn">{money(g.due, false)}{g.dueOn && <span className="block text-xs text-faint">by {date(g.dueOn)}</span>}</span>),
      },
    ];
    return (
      <ListPage<Schemas["GrnListItem"]>
        key="grns"
        title="Purchasing"
        actions={actions}
        path="/grns"
        queryKey="grns"
        columns={columns}
        filters={[{ kind: "dates" }, ...branchFilter(shop.branches), { kind: "check", param: "unpaid", label: "Unpaid" }]}
        rowHref={(g) => `/purchases/grns/${g.id}`}
        empty={{ title: "No goods received yet" }}
      >
        {tabs}
      </ListPage>
    );
  }
  const columns: Column<Schemas["PoListItem"]>[] = [
    { label: "Order", render: (p) => <span className="font-mono font-medium">{p.number}</span> },
    { label: "Date", render: (p) => <span className="text-muted">{date(p.date)}</span> },
    { label: "Supplier", render: (p) => p.supplierName },
    { label: "Expected", render: (p) => <span className="text-muted">{date(p.expectedOn)}</span> },
    { label: "Lines", render: (p) => p.lines, align: "right" },
    { label: "Total", render: (p) => money(p.total, false), align: "right" },
    { label: "Status", render: (p) => <Status value={p.status} /> },
  ];
  return (
    <ListPage<Schemas["PoListItem"]>
      key="orders"
      title="Purchasing"
      description={
        <>
          Orders to suppliers and the goods that came in. See also <ShopLink href="/purchases/reorder" className="text-aqua">what to reorder</ShopLink>.
        </>
      }
      actions={actions}
      path="/purchase-orders"
      queryKey="purchase-orders"
      columns={columns}
      filters={[
        {
          kind: "select",
          param: "status",
          label: "Status",
          options: [
            { value: "", label: "All" },
            { value: "ORDERED", label: "Ordered" },
            { value: "PARTIAL", label: "Part received" },
            { value: "RECEIVED", label: "Received" },
            { value: "CLOSED", label: "Closed" },
            { value: "CANCELLED", label: "Cancelled" },
          ],
        },
        ...branchFilter(shop.branches),
      ]}
      rowHref={(p) => `/purchases/${p.id}`}
      empty={{ title: "No purchase orders yet", description: "You can also receive goods without an order." }}
    >
      {tabs}
    </ListPage>
  );
}
