"use client";

import { useQuery } from "@tanstack/react-query";
import { Status } from "@/components/status";
import { Empty, ErrorState, PageHeader, Panel, StatCard, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { useShop } from "@/lib/shop-context";

/** The shop's plan, limits and SelectCode invoices (BIL-05). Payment is made to SelectCode directly. */
export default function SubscriptionPage() {
  const shop = useShop();
  const invoices = useQuery({ queryKey: ["my-invoices"], queryFn: () => api<Schemas["InvoiceView"][]>("/subscription/invoices") });
  const { usage, limits } = shop.plan;
  const of = (n: number, max?: number | null) => (max == null ? `${n.toLocaleString()} (no limit)` : `${n.toLocaleString()} of ${max.toLocaleString()}`);
  return (
    <>
      <PageHeader
        title="Subscription"
        description={`${shop.plan.name} plan · ${label(shop.shop.status)}${shop.shop.trialEndsOn ? ` · trial ends ${date(shop.shop.trialEndsOn)}` : ""}`}
      />
      {shop.banners.map((b, i) => (
        <p key={i} className="mb-3 rounded-xl bg-warn/10 px-4 py-3 text-sm text-warn">
          {b.message}
        </p>
      ))}
      {shop.shop.status === "READ_ONLY" && (
        <p className="mb-4 rounded-xl bg-bad/10 px-4 py-3 text-sm text-bad">
          The shop is read-only: you can see and export everything, but new sales and changes are blocked until the invoice is paid.
        </p>
      )}
      <div className="mb-4 grid gap-3 sm:grid-cols-4">
        <StatCard label="Products" value={of(usage.products, limits.products)} />
        <StatCard label="Users" value={of(usage.users, limits.users)} />
        <StatCard label="Branches" value={of(usage.branches, limits.branches)} />
        <StatCard label="Tills" value={of(usage.tills, limits.tills)} />
      </div>
      <Panel padded={false} title="Invoices" description="Pay by bank transfer, JazzCash or Easypaisa to SelectCode; we mark it paid and send a receipt.">
        {invoices.isPending ? (
          <TableSkeleton />
        ) : invoices.isError ? (
          <ErrorState message={errorMessage(invoices.error)} />
        ) : invoices.data.length === 0 ? (
          <Empty title="No invoices yet" />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Invoice</Th>
                <Th>Period</Th>
                <Th>Due</Th>
                <Th align="right">Total</Th>
                <Th align="right">Balance</Th>
                <Th>Status</Th>
              </tr>
            </thead>
            <tbody>
              {invoices.data.map((i) => (
                <tr key={i.id}>
                  <Td className="font-mono">{i.number}</Td>
                  <Td className="text-muted">
                    {date(i.periodFrom)} – {date(i.periodTo)}
                  </Td>
                  <Td className="text-muted">{date(i.dueOn)}</Td>
                  <Td align="right">{money(i.total, false)}</Td>
                  <Td align="right">{money(i.balance, false)}</Td>
                  <Td>
                    <Status value={i.status} />
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
    </>
  );
}
