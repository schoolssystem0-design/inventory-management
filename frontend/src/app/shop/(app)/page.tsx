"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Circle, PackagePlus, Receipt, ShoppingCart, Wallet } from "lucide-react";
import { LinkButton } from "@/components/ui/button";
import { Select } from "@/components/ui/fields";
import { Empty, PageHeader, Panel, Skeleton, StatCard } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { date, label, money, qty } from "@/lib/format";
import { P, ShopLink, useBranch, useCan, useShop, useShopHref } from "@/lib/shop-context";

/** Home (RPT-01): today's figures for what the user may see, the last 14 days, and the setup checklist. */
export default function HomePage() {
  const shop = useShop();
  const can = useCan();
  const toHref = useShopHref();
  const [branch, chooseBranch] = useBranch();
  const dash = useQuery({
    queryKey: ["dashboard", branch?.id ?? null],
    queryFn: () => api<Schemas["Dashboard"]>(`/dashboard${branch && !shop.user.allBranches ? `?branchId=${branch.id}` : ""}`),
    refetchInterval: 60_000,
  });
  const onboarding = useQuery({
    queryKey: ["onboarding"],
    queryFn: () => api<Schemas["Checklist"]>("/onboarding"),
    enabled: shop.user.owner && can(P.SETTINGS_VIEW),
  });
  const d = dash.data;
  const max = Math.max(1, ...(d?.last14Days ?? []).map((p) => Number(p.sales)));

  return (
    <>
      <PageHeader
        title={`Hello, ${shop.user.displayName.split(" ")[0]}`}
        description={`${shop.shop.name} · ${shop.shop.typeName} · ${label(shop.shop.status)} · ${shop.plan.name} plan`}
        actions={
          <>
            {!shop.user.allBranches && shop.branches.length > 1 && branch && (
              <Select value={branch.id} onChange={(e) => chooseBranch(Number(e.target.value))} className="w-auto" aria-label="Branch">
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            )}
            {can(P.POS_SELL) && (
              <LinkButton href={toHref("/pos")}>
                <ShoppingCart /> Sell
              </LinkButton>
            )}
          </>
        }
      />

      {onboarding.data && !onboarding.data.complete && (
        <Panel title="Get your shop ready" description={`${onboarding.data.done} of ${onboarding.data.total} done`} className="mb-6">
          <div className="mb-3 h-2 overflow-hidden rounded-full bg-raised">
            <div className="h-full rounded-full bg-aqua transition-all" style={{ width: `${(onboarding.data.done / Math.max(1, onboarding.data.total)) * 100}%` }} />
          </div>
          <ul className="grid gap-1 sm:grid-cols-2">
            {onboarding.data.steps.map((s) => (
              <li key={s.key}>
                <ShopLink href={s.link || "/"} className="flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-raised">
                  {s.done ? <CheckCircle2 className="size-5 text-good" aria-label="Done" /> : <Circle className="size-5 text-faint" aria-label="To do" />}
                  <span className={s.done ? "text-muted line-through" : "text-ink"}>{s.label}</span>
                </ShopLink>
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {dash.isPending && <Skeleton className="h-64" />}
      {d && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {d.salesToday != null && <StatCard label="Sales today" value={money(d.salesToday)} hint={`${d.invoicesToday ?? 0} bills · average ${money(d.averageBill)}`} tone="aqua" href={toHref("/sales")} />}
            {d.profitToday != null && <StatCard label="Profit today" value={money(d.profitToday)} tone="good" />}
            {d.cashInDrawers != null && <StatCard label="Cash in the drawers" value={money(d.cashInDrawers)} hint={`${d.openTills ?? 0} ${d.openTills === 1 ? "till" : "tills"} open`} href={toHref("/till")} />}
            {d.returnsToday != null && d.returnsToday !== "0.00" && <StatCard label="Returns today" value={money(d.returnsToday)} tone="warn" />}
            {d.customerDues != null && <StatCard label="Customers owe you" value={money(d.customerDues)} href={toHref("/customers")} />}
            {d.supplierDues != null && <StatCard label="You owe suppliers" value={money(d.supplierDues)} href={toHref("/suppliers")} />}
            {d.lowStock != null && (
              <StatCard
                label="Stock alerts"
                value={(d.lowStock ?? 0) + (d.outOfStock ?? 0) + (d.expiringSoon ?? 0)}
                hint={`${d.lowStock} low · ${d.outOfStock} out · ${d.expiringSoon} expiring`}
                tone={(d.outOfStock ?? 0) > 0 ? "bad" : (d.lowStock ?? 0) > 0 ? "warn" : undefined}
                href={toHref("/stock/alerts")}
              />
            )}
            {d.transfersInTransit != null && d.transfersInTransit > 0 && <StatCard label="Transfers on the way" value={d.transfersInTransit} href={toHref("/stock/transfers")} />}
          </div>

          {d.last14Days?.length > 0 && (
            <div className="mt-6 grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
              <Panel title="Last 14 days" description="Sales by day">
                <div className="flex h-48 items-end gap-1.5" role="img" aria-label="Sales for the last 14 days">
                  {d.last14Days.map((p) => (
                    <div key={p.date} className="group flex h-full flex-1 flex-col items-center justify-end gap-1">
                      <span className="hidden text-[10px] text-faint group-hover:block">{money(p.sales, false)}</span>
                      <div className="w-full rounded-t-md bg-aqua/70 group-hover:bg-aqua" style={{ height: `${Math.max(2, (Number(p.sales) / max) * 100)}%` }} title={`${date(p.date)}: ${money(p.sales)} · ${p.invoices} bills`} />
                      <span className="text-[10px] text-faint">{p.date.slice(8)}</span>
                    </div>
                  ))}
                </div>
              </Panel>
              <Panel title="Top items today">
                {d.topItems?.length ? (
                  <ol className="space-y-2 text-sm">
                    {d.topItems.map((t, i) => (
                      <li key={t.productId} className="flex justify-between gap-3">
                        <span className="truncate">
                          <span className="mr-2 text-faint">{i + 1}</span>
                          {t.name}
                        </span>
                        <span className="shrink-0 font-mono text-muted">
                          {qty(t.qty)} · {money(t.amount, false)}
                        </span>
                      </li>
                    ))}
                  </ol>
                ) : (
                  <Empty title="No sales yet today" />
                )}
              </Panel>
            </div>
          )}
        </>
      )}

      <div className="mt-6 flex flex-wrap gap-2">
        {can(P.PURCHASING_MANAGE) && (
          <LinkButton variant="secondary" href={toHref("/purchases/receive")}>
            <PackagePlus /> Receive goods
          </LinkButton>
        )}
        {can(P.EXPENSES_MANAGE) && (
          <LinkButton variant="secondary" href={toHref("/expenses")}>
            <Wallet /> Add an expense
          </LinkButton>
        )}
        {can(P.SALES_VIEW) && (
          <LinkButton variant="secondary" href={toHref("/sales")}>
            <Receipt /> Today&apos;s sales
          </LinkButton>
        )}
      </div>
    </>
  );
}
