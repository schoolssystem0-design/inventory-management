"use client";

import { useQuery } from "@tanstack/react-query";
import * as React from "react";
import { todayIso } from "@/components/list-page";
import { Status } from "@/components/status";
import { Field, Input } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Tabs, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { addMoney, ZERO } from "@/lib/money";
import { ShopLink, useShopHref } from "@/lib/shop-context";

type Row = Schemas["InstallmentDueRow"];

/** Installments to collect: late ones (past their grace days) and those due soon. */
export default function DueInstallmentsPage() {
  const toHref = useShopHref();
  const [tab, setTab] = React.useState<"late" | "due">("late");
  const [to, setTo] = React.useState(() => {
    const d = new Date(Date.now() + 7 * 86_400_000);
    return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Karachi" }).format(d);
  });
  const list = useQuery({
    queryKey: ["installments-due", tab, to],
    queryFn: () => api<Row[]>(tab === "late" ? "/installments/due?overdue=true" : `/installments/due?to=${to}`),
  });
  const total = (list.data ?? []).reduce((s, r) => addMoney(s, r.left), ZERO);
  return (
    <>
      <PageHeader back={{ href: toHref("/installments"), label: "Installments" }} title="Due and late installments" description={list.data ? `${list.data.length} installments · ${money(total)} to collect` : undefined} />
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "late", label: "Late" },
            { value: "due", label: "Due up to a date" },
          ]}
        />
        {tab === "due" && (
          <Field label="Up to" className="w-44">
            <Input type="date" value={to} min={todayIso()} onChange={(e) => setTo(e.target.value)} />
          </Field>
        )}
      </div>
      <Panel padded={false}>
        {list.isPending ? (
          <TableSkeleton />
        ) : list.isError ? (
          <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <Empty title={tab === "late" ? "Nothing late" : "Nothing due"} />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Customer</Th>
                <Th>Plan</Th>
                <Th align="right">No.</Th>
                <Th>Due</Th>
                <Th>State</Th>
                <Th align="right">Late fee</Th>
                <Th align="right">To collect</Th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((r) => (
                <tr key={`${r.planId}-${r.no}`}>
                  <Td>
                    <ShopLink href={`/customers/${r.customerId}`} className="hover:text-aqua">
                      {r.customerName}
                    </ShopLink>
                    <span className="block text-xs text-faint">{r.customerPhone}</span>
                  </Td>
                  <Td>
                    <ShopLink href={`/installments/${r.planId}`} className="font-mono hover:text-aqua">
                      {r.planNumber}
                    </ShopLink>
                  </Td>
                  <Td align="right">{r.no}</Td>
                  <Td>{date(r.dueOn)}</Td>
                  <Td>{r.daysLate > 0 ? <span className="text-bad">{r.daysLate} days late</span> : <Status value="DUE" />}</Td>
                  <Td align="right">{r.lateFee === "0.00" ? "—" : money(r.lateFee, false)}</Td>
                  <Td align="right" className="font-medium">
                    {money(r.left, false)}
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
