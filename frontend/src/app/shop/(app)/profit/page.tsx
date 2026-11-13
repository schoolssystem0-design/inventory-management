"use client";

import { useQuery } from "@tanstack/react-query";
import * as React from "react";
import { daysAgoIso, todayIso } from "@/components/list-page";
import { Input, Select } from "@/components/ui/fields";
import { ErrorState, PageHeader, Panel, Skeleton, StatCard, Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { useShop } from "@/lib/shop-context";

/** Profit and loss (CSH-04): by month, from sales less returns, cost of what was sold, stock losses and expenses. */
export default function ProfitPage() {
  const shop = useShop();
  const [from, setFrom] = React.useState(`${daysAgoIso(150).slice(0, 7)}-01`);
  const [to, setTo] = React.useState(todayIso());
  const [branchId, setBranchId] = React.useState("");
  const pl = useQuery({
    queryKey: ["profit-loss", from, to, branchId],
    queryFn: () => api<Schemas["ProfitLoss"]>(`/cash/profit-loss?from=${from}&to=${to}${branchId ? `&branchId=${branchId}` : ""}`),
  });
  const t = pl.data?.total;
  return (
    <>
      <PageHeader
        title="Profit and loss"
        actions={
          <>
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-auto" aria-label="From" />
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-auto" aria-label="To" />
            {shop.branches.length > 1 && (
              <Select value={branchId} onChange={(e) => setBranchId(e.target.value)} className="w-auto" aria-label="Branch">
                <option value="">All branches</option>
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            )}
          </>
        }
      />
      {pl.isPending && <Skeleton className="h-80" />}
      {pl.isError && <ErrorState message={errorMessage(pl.error)} onRetry={() => pl.refetch()} />}
      {t && pl.data && (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-4">
            <StatCard label="Net sales" value={money(t.netSales)} />
            <StatCard label="Gross profit" value={money(t.grossProfit)} tone="aqua" />
            <StatCard label="Expenses" value={money(t.expenses)} />
            <StatCard label="Net profit" value={money(t.netProfit)} tone={t.netProfit.startsWith("-") ? "bad" : "good"} />
          </div>
          <Panel padded={false} title="By month">
            <Table>
              <thead>
                <tr>
                  <Th>Month</Th>
                  <Th align="right">Sales</Th>
                  <Th align="right">Returns</Th>
                  <Th align="right">Net sales</Th>
                  <Th align="right">Cost of sales</Th>
                  <Th align="right">Gross profit</Th>
                  <Th align="right">Stock loss</Th>
                  <Th align="right">Expenses</Th>
                  <Th align="right">Net profit</Th>
                </tr>
              </thead>
              <tbody>
                {[...pl.data.months, { ...t, month: "Total" }].map((m) => (
                  <tr key={m.month} className={m.month === "Total" ? "font-semibold" : undefined}>
                    <Td>{m.month}</Td>
                    <Td align="right">{money(m.grossSales, false)}</Td>
                    <Td align="right">{money(m.returns, false)}</Td>
                    <Td align="right">{money(m.netSales, false)}</Td>
                    <Td align="right">{money(m.costOfSales, false)}</Td>
                    <Td align="right">{money(m.grossProfit, false)}</Td>
                    <Td align="right">{money(m.stockLoss, false)}</Td>
                    <Td align="right">{money(m.expenses, false)}</Td>
                    <Td align="right" className={m.netProfit.startsWith("-") ? "text-bad" : "text-good"}>
                      {money(m.netProfit, false)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </Panel>
          {pl.data.expensesByCategory.length > 0 && (
            <Panel title="Expenses by category" className="mt-4">
              {pl.data.expensesByCategory.map((e) => (
                <div key={e.method} className="flex justify-between py-1 text-sm">
                  <span className="text-muted">{e.method}</span>
                  <span className="font-mono">{money(e.amount)}</span>
                </div>
              ))}
            </Panel>
          )}
        </>
      )}
    </>
  );
}
