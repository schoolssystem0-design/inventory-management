"use client";

import { ShoppingCart } from "lucide-react";
import { branchFilter, type Column, daysAgoIso, ListPage, todayIso } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import type { Schemas } from "@/lib/api/client";
import { dateTime, money } from "@/lib/format";
import { P, useCan, useShop, useShopHref } from "@/lib/shop-context";

type Row = Schemas["SaleListItem"];

export default function SalesPage() {
  const can = useCan();
  const toHref = useShopHref();
  const shop = useShop();
  const columns: Column<Row>[] = [
    { label: "Invoice", render: (s) => <span className="font-mono font-medium text-ink">{s.number}</span> },
    { label: "When", render: (s) => <span className="text-muted">{dateTime(s.at)}</span> },
    { label: "Customer", render: (s) => s.customerName ?? <span className="text-faint">Walk-in</span> },
    { label: "Cashier", render: (s) => <span className="text-muted">{s.cashierName}</span> },
    { label: "Items", render: (s) => s.items, align: "right" },
    { label: "Total", render: (s) => money(s.total, false), align: "right" },
    { label: "Due", render: (s) => (s.due !== "0.00" ? <span className="text-warn">{money(s.due, false)}</span> : "—"), align: "right" },
    { label: "Profit", render: (s) => money(s.profit, false), align: "right", hidden: !can(P.COST_VIEW) },
    { label: "Status", render: (s) => <Status value={s.status === "POSTED" ? "COMPLETED" : s.status} /> },
  ];
  return (
    <ListPage<Row>
      title="Sales and returns"
      description="Every bill. Open one to reprint, return, exchange or cancel it."
      path="/sales"
      queryKey="sales"
      columns={columns}
      filters={[
        { kind: "search", placeholder: "Invoice no., customer, phone or IMEI" },
        { kind: "dates", from: daysAgoIso(6), to: todayIso() },
        {
          kind: "select",
          param: "status",
          label: "Status",
          options: [
            { value: "", label: "All statuses" },
            { value: "POSTED", label: "Completed" },
            { value: "CANCELLED", label: "Cancelled" },
          ],
        },
        ...branchFilter(shop.branches),
        { kind: "check", param: "withDue", label: "With amount due" },
      ]}
      rowHref={(s) => `/sales/${s.id}`}
      summary={(d) => (
        <span>
          {d.total.toLocaleString()} bills · total <span className="font-mono text-ink">{money(d.totalAmount as string)}</span> · due{" "}
          <span className="font-mono text-warn">{money(d.totalDue as string)}</span>
        </span>
      )}
      empty={{
        title: "No sales in these dates",
        action: can(P.POS_SELL) ? (
          <LinkButton href={toHref("/pos")}>
            <ShoppingCart /> Open the till
          </LinkButton>
        ) : undefined,
      }}
    />
  );
}
