"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import * as React from "react";
import { Input, Select } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Pager, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, errorMessage } from "@/lib/api/client";
import { useShopRouter } from "@/lib/shop-context";

export type Column<T> = {
  label: string;
  render: (row: T) => React.ReactNode;
  align?: "right";
  className?: string;
  hidden?: boolean;
};

export type Filter =
  | { kind: "search"; param?: string; placeholder: string }
  | { kind: "select"; param: string; label: string; options: { value: string; label: string }[]; initial?: string }
  | { kind: "dates"; from?: string; to?: string }
  | { kind: "check"; param: string; label: string };

type PageLike<T> = { items: T[]; total: number; page?: number; size?: number };

/** "All branches" plus each branch the user can see; hidden when there is only one. */
export function branchFilter(branches: { id: number; name: string }[]): Filter[] {
  if (branches.length < 2) return [];
  return [
    {
      kind: "select",
      param: "branchId",
      label: "Branch",
      options: [{ value: "", label: "All branches" }, ...branches.map((b) => ({ value: String(b.id), label: b.name }))],
    },
  ];
}

export function todayIso() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Karachi" }).format(new Date());
}

export function daysAgoIso(days: number) {
  const d = new Date(Date.now() - days * 86_400_000);
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Karachi" }).format(d);
}

/** Filters, a paged table and row links: the shape of every list in the shop app. */
export function ListPage<T extends object>({
  title,
  description,
  actions,
  path,
  queryKey,
  columns,
  filters = [],
  extraParams,
  rowHref,
  empty,
  summary,
  size = 50,
  children,
}: {
  title: string;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** API path without query. */
  path: string;
  queryKey: string;
  columns: Column<T>[];
  filters?: Filter[];
  extraParams?: Record<string, string | number | null | undefined>;
  rowHref?: (row: T) => string | null;
  empty: { title: string; description?: React.ReactNode; action?: React.ReactNode };
  /** A line above the table from the whole response (totals). */
  summary?: (data: PageLike<T> & Record<string, unknown>) => React.ReactNode;
  size?: number;
  children?: React.ReactNode;
}) {
  const router = useShopRouter();
  const [values, setValues] = React.useState<Record<string, string>>(() => {
    const v: Record<string, string> = {};
    for (const f of filters) {
      if (f.kind === "select" && f.initial) v[f.param] = f.initial;
      if (f.kind === "dates") {
        if (f.from) v.from = f.from;
        if (f.to) v.to = f.to;
      }
    }
    return v;
  });
  const [text, setText] = React.useState("");
  const [page, setPage] = React.useState(0);
  const searchParam = (filters.find((f) => f.kind === "search") as { param?: string } | undefined)?.param ?? "q";

  React.useEffect(() => {
    const t = setTimeout(() => {
      setValues((v) => (v[searchParam] === text.trim() ? v : { ...v, [searchParam]: text.trim() }));
      setPage(0);
    }, 250);
    return () => clearTimeout(t);
  }, [text, searchParam]);

  const params = new URLSearchParams({ page: String(page), size: String(size) });
  for (const [k, v] of Object.entries({ ...values, ...extraParams })) {
    if (v !== undefined && v !== null && v !== "") params.set(k, String(v));
  }
  const query = useQuery({
    queryKey: [queryKey, path, params.toString()],
    queryFn: () => api<PageLike<T> & Record<string, unknown>>(`${path}?${params}`),
    placeholderData: keepPreviousData,
  });
  const set = (k: string, v: string) => {
    setValues((s) => ({ ...s, [k]: v }));
    setPage(0);
  };
  const visible = columns.filter((c) => !c.hidden);
  const filtered = Object.entries(values).some(([k, v]) => v && filters.some((f) => (f.kind === "search" && k === searchParam) || (f.kind !== "search" && f.kind !== "dates" && f.param === k)));

  return (
    <>
      <PageHeader title={title} description={description} actions={actions} />
      {children}
      <Panel padded={false}>
        {filters.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 border-b border-line p-3">
            {filters.map((f, i) => {
              if (f.kind === "search") {
                return (
                  <div key={i} className="relative min-w-52 flex-1">
                    <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
                    <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={f.placeholder} aria-label={f.placeholder} className="pl-9" />
                  </div>
                );
              }
              if (f.kind === "select") {
                return (
                  <Select key={i} value={values[f.param] ?? ""} onChange={(e) => set(f.param, e.target.value)} aria-label={f.label} className="w-auto min-w-36">
                    {f.options.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </Select>
                );
              }
              if (f.kind === "check") {
                return (
                  <label key={i} className="flex items-center gap-2 px-2 text-sm text-muted">
                    <input type="checkbox" className="accent-[#4fbfae]" checked={values[f.param] === "true"} onChange={(e) => set(f.param, e.target.checked ? "true" : "")} />
                    {f.label}
                  </label>
                );
              }
              return (
                <div key={i} className="flex items-center gap-1">
                  <Input type="date" value={values.from ?? ""} onChange={(e) => set("from", e.target.value)} aria-label="From" className="w-auto" />
                  <span className="text-faint">–</span>
                  <Input type="date" value={values.to ?? ""} onChange={(e) => set("to", e.target.value)} aria-label="To" className="w-auto" />
                </div>
              );
            })}
          </div>
        )}
        {query.data && summary && <div className="border-b border-line px-4 py-2 text-sm text-muted">{summary(query.data)}</div>}
        {query.isPending ? (
          <TableSkeleton cols={Math.min(visible.length, 6)} />
        ) : query.isError ? (
          <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
        ) : query.data.items.length === 0 ? (
          <Empty title={filtered ? "Nothing matches" : empty.title} description={filtered ? "Try other filters." : empty.description} action={filtered ? undefined : empty.action} />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  {visible.map((c) => (
                    <Th key={c.label} align={c.align}>
                      {c.label}
                    </Th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((row, i) => {
                  const href = rowHref?.(row) ?? null;
                  return (
                    <tr
                      key={(row as { id?: number | string; productId?: number }).id ?? (row as { productId?: number }).productId ?? i}
                      className={href ? "cursor-pointer hover:bg-raised/40" : undefined}
                      onClick={href ? (e) => {
                        if ((e.target as HTMLElement).closest("a,button,input")) return;
                        router.push(href);
                      } : undefined}
                    >
                      {visible.map((c) => (
                        <Td key={c.label} align={c.align} className={c.className}>
                          {c.render(row)}
                        </Td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            </Table>
            <Pager page={page} size={size} total={query.data.total} onPage={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
