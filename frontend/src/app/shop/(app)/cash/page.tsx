"use client";

import { useQuery } from "@tanstack/react-query";
import * as React from "react";
import { todayIso } from "@/components/list-page";
import { Input, Select } from "@/components/ui/fields";
import { ErrorState, PageHeader, Panel, Skeleton, StatCard } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { label, money } from "@/lib/format";
import { useShop } from "@/lib/shop-context";

/** Daily cash (CSH-02): where the day's cash came from and went, against what was counted. */
export default function DailyCashPage() {
  const shop = useShop();
  const [day, setDay] = React.useState(todayIso());
  const [branchId, setBranchId] = React.useState("");
  const cash = useQuery({
    queryKey: ["daily-cash", day, branchId],
    queryFn: () => api<Schemas["DailyCash"]>(`/cash/daily?date=${day}${branchId ? `&branchId=${branchId}` : ""}`),
  });
  const d = cash.data;
  const row = (k: string, v: string | null | undefined, sign?: "+" | "−", strong = false) => (
    <div className={`flex justify-between py-1.5 ${strong ? "border-t border-line pt-2.5 font-semibold" : ""}`}>
      <span className={strong ? "" : "text-muted"}>
        {sign && <span className="mr-2 font-mono text-faint">{sign}</span>}
        {k}
      </span>
      <span className="font-mono">{money(v)}</span>
    </div>
  );
  return (
    <>
      <PageHeader
        title="Daily cash"
        description="Every rupee in and out of the drawers for the day."
        actions={
          <>
            <Input type="date" value={day} onChange={(e) => setDay(e.target.value)} className="w-auto" aria-label="Day" />
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
      {cash.isPending && <Skeleton className="h-80" />}
      {cash.isError && <ErrorState message={errorMessage(cash.error)} onRetry={() => cash.refetch()} />}
      {d && (
        <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
          <Panel title="Cash">
            {row("Opening float", d.opening)}
            {row("Cash sales", d.cashSales, "+")}
            {row("Cash received from customers", d.cashReceipts, "+")}
            {row("Cash put in", d.cashIn, "+")}
            {row("Cash refunds", d.cashRefunds, "−")}
            {row("Cash taken out", d.cashOut, "−")}
            {row("Expenses paid in cash", d.expenses, "−")}
            {row("Suppliers paid in cash", d.supplierPayments, "−")}
            {row("Used phones bought", d.buybacks, "−")}
            {row("Expected in the drawers", d.expectedClosing, undefined, true)}
            {row("Counted at closing", d.counted)}
            {d.difference != null && (
              <div className="flex justify-between py-1.5 font-semibold">
                <span>Difference</span>
                <span className={`font-mono ${d.difference.startsWith("-") ? "text-bad" : d.difference === "0.00" ? "text-good" : "text-warn"}`}>{money(d.difference)}</span>
              </div>
            )}
          </Panel>
          <div className="space-y-3">
            <StatCard label="Tills still open" value={d.openTills} tone={d.openTills ? "warn" : "good"} />
            <Panel title="Other takings">
              {d.otherTakings.length === 0 && <p className="text-sm text-faint">Card and wallet payments appear here.</p>}
              {d.otherTakings.map((m) => (
                <div key={m.method} className="flex justify-between py-1 text-sm">
                  <span className="text-muted">{label(m.method)}</span>
                  <span className="font-mono">{money(m.amount)}</span>
                </div>
              ))}
            </Panel>
          </div>
        </div>
      )}
    </>
  );
}
