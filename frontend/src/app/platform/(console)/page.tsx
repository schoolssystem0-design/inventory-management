"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Badge, ErrorState, PageHeader, Panel, Skeleton, StatCard, statusTone } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, money, plural } from "@/lib/format";
import { consoleHref } from "@/lib/routing";

/** PLT-04: shops by status, type and city, recurring revenue, overdue, and trials or payments due this week. */
export default function OverviewPage() {
  const overview = useQuery({ queryKey: ["platform-overview"], queryFn: () => api<Schemas["Overview"]>("/platform/overview") });
  if (overview.isPending) return <Skeleton className="h-96" />;
  if (overview.isError) return <ErrorState message={errorMessage(overview.error)} onRetry={() => overview.refetch()} />;
  const o = overview.data;
  const count = (key: string) => o.byStatus.find((s) => s.key === key)?.count ?? 0;
  const max = Math.max(1, ...o.byType.map((t) => t.count));
  return (
    <>
      <PageHeader title="Overview" description="All shops on SelectCode POS" />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Shops" value={o.totalShops} hint={`${o.newShopsThisMonth} new this month`} href={consoleHref("/shops")} tone="aqua" />
        <StatCard
          label="Active / trial"
          value={`${count("ACTIVE")} / ${count("TRIAL")}`}
          href={consoleHref("/shops?status=ACTIVE")}
        />
        <StatCard label="Monthly recurring" value={money(o.monthlyRecurringRevenue)} tone="good" />
        <StatCard label="Overdue" value={money(o.overdueAmount)} hint={plural(o.overdueShops, "shop")} tone={o.overdueShops ? "warn" : undefined} />
      </div>
      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <Panel title="Shops by type">
          <ul className="space-y-2">
            {o.byType.map((t) => (
              <li key={t.key} className="grid grid-cols-[10rem_1fr_2.5rem] items-center gap-3 text-sm">
                <span className="truncate text-muted">{t.label}</span>
                <span className="h-2 rounded-full bg-raised">
                  <span className="block h-2 rounded-full bg-aqua" style={{ width: `${(t.count / max) * 100}%` }} />
                </span>
                <span className="text-right font-mono">{t.count}</span>
              </li>
            ))}
          </ul>
        </Panel>
        <Panel title="Due in the next 7 days" padded={false}>
          {o.expiringSoon.length === 0 ? (
            <p className="p-5 text-sm text-muted">No trials ending or payments due this week.</p>
          ) : (
            <ul className="divide-y divide-line">
              {o.expiringSoon.map((s) => (
                <li key={s.kind + s.id}>
                  <Link href={consoleHref(`/shops/${s.id}`)} className="flex items-center justify-between gap-3 px-5 py-3 hover:bg-raised/40">
                    <span className="min-w-0">
                      <span className="block truncate text-sm font-medium">{s.name}</span>
                      <span className="text-xs text-faint">{s.kind === "TRIAL_ENDS" ? "Trial ends" : "Payment due"} {date(s.date)}</span>
                    </span>
                    <Badge tone={statusTone(s.status)}>{s.status}</Badge>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Shops by status">
          <div className="flex flex-wrap gap-2">
            {o.byStatus.map((s) => (
              <Link key={s.key} href={consoleHref(`/shops?status=${s.key}`)}>
                <Badge tone={statusTone(s.key)}>
                  {s.label}: {s.count}
                </Badge>
              </Link>
            ))}
          </div>
        </Panel>
        <Panel title="Top cities">
          <ul className="space-y-1 text-sm">
            {o.byCity.map((c) => (
              <li key={c.key} className="flex justify-between">
                <span className="text-muted">{c.label}</span>
                <span className="font-mono">{c.count}</span>
              </li>
            ))}
          </ul>
        </Panel>
      </div>
    </>
  );
}
