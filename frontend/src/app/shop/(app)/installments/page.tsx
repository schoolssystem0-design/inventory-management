"use client";

import { CalendarClock } from "lucide-react";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import { type Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { useShopHref } from "@/lib/shop-context";

type Plan = Schemas["InstallmentPlanView"];

/** Installment plans (MOB-08, ELE-03). A plan is made from an invoice left on the customer's account. */
export default function InstallmentsPage() {
  const toHref = useShopHref();
  const columns: Column<Plan>[] = [
    { label: "Plan", render: (p) => <span className="font-mono font-medium">{p.number}</span> },
    {
      label: "Customer",
      render: (p) => (
        <>
          {p.customerName}
          <span className="block text-xs text-faint">{p.customerPhone}</span>
        </>
      ),
    },
    { label: "Invoice", render: (p) => <span className="font-mono text-muted">{p.saleNumber}</span> },
    { label: "Months", align: "right", render: (p) => p.months },
    { label: "Total", align: "right", render: (p) => money(p.total, false) },
    { label: "Left", align: "right", render: (p) => money(p.left, false) },
    {
      label: "Next due",
      render: (p) =>
        p.nextDueOn ? (
          <>
            {date(p.nextDueOn)}
            <span className="block text-xs text-faint">{money(p.nextDueAmount)}</span>
          </>
        ) : (
          "—"
        ),
    },
    { label: "Late", render: (p) => (p.overdueCount ? <span className="text-bad">{p.overdueCount} · {money(p.overdueAmount)}</span> : "—") },
    { label: "Status", render: (p) => <Status value={p.status} /> },
  ];
  return (
    <ListPage<Plan>
      title="Installments"
      description="Make a plan from an invoice on the customer's account (open the invoice, then Installment plan)."
      path="/installment-plans"
      queryKey="installment-plans"
      columns={columns}
      rowHref={(p) => `/installments/${p.id}`}
      actions={
        <LinkButton variant="secondary" href={toHref("/installments/due")}>
          <CalendarClock /> Due and late
        </LinkButton>
      }
      filters={[
        { kind: "search", placeholder: "Plan no., customer or phone" },
        {
          kind: "select",
          param: "status",
          label: "Status",
          initial: "ACTIVE",
          options: [
            { value: "ACTIVE", label: "Active" },
            { value: "COMPLETED", label: "Completed" },
            { value: "CANCELLED", label: "Cancelled" },
            { value: "", label: "All" },
          ],
        },
      ]}
      empty={{ title: "No installment plans", description: "Sell on the customer's account, then make a plan from the invoice." }}
    />
  );
}
