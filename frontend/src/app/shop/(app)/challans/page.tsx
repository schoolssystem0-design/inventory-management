"use client";

import { Plus } from "lucide-react";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import { type Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

type Challan = Schemas["ChallanView"];

/** Delivery challans (HRD-04): goods sent before the bill; stock leaves at once, the bill comes later. */
export default function ChallansPage() {
  const can = useCan();
  const toHref = useShopHref();
  const columns: Column<Challan>[] = [
    { label: "Challan", render: (c) => <span className="font-mono font-medium">{c.number}</span> },
    { label: "Date", render: (c) => <span className="text-muted">{date(c.date)}</span> },
    {
      label: "Customer",
      render: (c) => (
        <>
          {c.customerName}
          {c.address && <span className="block text-xs text-faint">{c.address}</span>}
        </>
      ),
    },
    { label: "Items", align: "right", render: (c) => c.lines.length },
    { label: "Value", align: "right", render: (c) => money(c.total, false) },
    { label: "Invoice", render: (c) => (c.saleNumber ? <span className="font-mono">{c.saleNumber}</span> : "—") },
    { label: "Status", render: (c) => <Status value={c.status} /> },
  ];
  return (
    <ListPage<Challan>
      title="Delivery challans"
      description="Goods sent to a customer before billing. Bill the challan when the order is complete."
      path="/challans"
      queryKey="challans"
      columns={columns}
      rowHref={(c) => `/challans/${c.id}`}
      actions={
        can(P.DELIVERY_MANAGE) && (
          <LinkButton href={toHref("/challans/new")}>
            <Plus /> New challan
          </LinkButton>
        )
      }
      filters={[
        { kind: "search", placeholder: "Challan no. or customer" },
        {
          kind: "select",
          param: "status",
          label: "Status",
          initial: "OPEN",
          options: [
            { value: "OPEN", label: "Not billed yet" },
            { value: "INVOICED", label: "Billed" },
            { value: "CANCELLED", label: "Cancelled" },
            { value: "", label: "All" },
          ],
        },
      ]}
      empty={{ title: "No challans", description: "Send goods on a challan when the customer is billed later (contractors, site orders)." }}
    />
  );
}
