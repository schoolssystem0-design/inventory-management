"use client";

import { useQuery } from "@tanstack/react-query";
import { BarChart3, Search } from "lucide-react";
import * as React from "react";
import { Input } from "@/components/ui/fields";
import { ErrorState, PageHeader, Panel, Skeleton } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { ShopLink } from "@/lib/shop-context";

/** All reports (RPT-02), grouped; each opens with filters and Excel/PDF export. */
export default function ReportsPage() {
  const reports = useQuery({ queryKey: ["reports"], queryFn: () => api<Schemas["ReportInfo"][]>("/reports") });
  const [q, setQ] = React.useState("");
  const list = (reports.data ?? []).filter((r) => !q || `${r.title} ${r.description} ${r.group}`.toLowerCase().includes(q.toLowerCase()));
  const groups = [...new Set(list.map((r) => r.group))];
  return (
    <>
      <PageHeader title="Reports" description={reports.data ? `${reports.data.length} reports for your shop` : undefined} />
      <div className="relative mb-4 max-w-md">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Find a report" className="pl-9" aria-label="Find a report" autoFocus />
      </div>
      {reports.isPending && <Skeleton className="h-96" />}
      {reports.isError && <ErrorState message={errorMessage(reports.error)} onRetry={() => reports.refetch()} />}
      <div className="grid gap-4 lg:grid-cols-2">
        {groups.map((g) => (
          <Panel key={g} title={g}>
            <ul className="-mx-2 space-y-0.5">
              {list
                .filter((r) => r.group === g)
                .map((r) => (
                  <li key={r.key}>
                    <ShopLink href={`/reports/${r.key}`} className="flex gap-3 rounded-lg px-2 py-2 hover:bg-raised">
                      <BarChart3 className="mt-0.5 size-4 shrink-0 text-aqua" aria-hidden />
                      <span>
                        <span className="block text-sm font-medium text-ink">{r.title}</span>
                        <span className="block text-xs text-muted">{r.description}</span>
                      </span>
                    </ShopLink>
                  </li>
                ))}
            </ul>
          </Panel>
        ))}
      </div>
    </>
  );
}
