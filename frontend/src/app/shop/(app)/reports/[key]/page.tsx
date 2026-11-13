"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { CloudDownload, FileSpreadsheet, FileText } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { daysAgoIso, todayIso } from "@/components/list-page";
import { Button } from "@/components/ui/button";
import { Field, Input, Select } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, apiBlob, ApiError, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { date, money, qty } from "@/lib/format";
import { useShop, useShopHref, useShopRouter } from "@/lib/shop-context";

function cell(kind: string, v: unknown) {
  if (v === null || v === undefined || v === "") return "—";
  const s = String(v);
  switch (kind) {
    case "MONEY":
      return money(s, false);
    case "QTY":
      return qty(s);
    case "PERCENT":
      return `${s}%`;
    case "DATE":
      return date(s);
    case "INT":
      return Number(s).toLocaleString();
    default:
      return s;
  }
}

const RIGHT = new Set(["MONEY", "QTY", "PERCENT", "INT"]);

export default function ReportPage() {
  const { key } = useParams<{ key: string }>();
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const info = useQuery({ queryKey: ["reports"], queryFn: () => api<Schemas["ReportInfo"][]>("/reports") });
  const meta = info.data?.find((r) => r.key === key);
  const [from, setFrom] = React.useState(daysAgoIso(29));
  const [to, setTo] = React.useState(todayIso());
  const [branchId, setBranchId] = React.useState("");
  const [extra, setExtra] = React.useState<Record<string, string>>({});
  const params = new URLSearchParams({ from, to });
  if (branchId) params.set("branchId", branchId);
  for (const [k, v] of Object.entries(extra)) if (v) params.set(k, v);
  const result = useQuery({ queryKey: ["report", key, params.toString()], queryFn: () => api<Schemas["ReportResult"]>(`/reports/${key}?${params}`) });
  const background = useMutation({
    mutationFn: (format: string) =>
      api(`/reports/${key}/export`, { method: "POST", body: { format, from, to, branchId: branchId ? Number(branchId) : null, params: extra } }),
    onSuccess: () => {
      toast.success("Export started — it will be on the Exports page shortly");
      router.push("/exports");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  async function download(format: "XLSX" | "PDF") {
    try {
      saveBlob(await apiBlob(`/reports/${key}/download?${params}&format=${format}`), `${key}.${format === "PDF" ? "pdf" : "xlsx"}`);
    }
    catch (e) {
      if (e instanceof ApiError && e.code === "too_many_rows") background.mutate(format);
      else toast.error(errorMessage(e));
    }
  }
  const r = result.data;
  return (
    <>
      <PageHeader
        back={{ href: toHref("/reports"), label: "Reports" }}
        title={meta?.title ?? r?.title ?? "Report"}
        description={meta?.description}
        actions={
          <>
            <Button variant="secondary" onClick={() => download("XLSX")}>
              <FileSpreadsheet /> Excel
            </Button>
            <Button variant="secondary" onClick={() => download("PDF")}>
              <FileText /> PDF
            </Button>
            {r?.truncated && (
              <Button onClick={() => background.mutate("XLSX")} loading={background.isPending}>
                <CloudDownload /> Export all {r.rowCount.toLocaleString()} rows
              </Button>
            )}
          </>
        }
      />
      <Panel className="mb-4">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="From">
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label="To">
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </Field>
          {shop.branches.length > 1 && (
            <Field label="Branch">
              <Select value={branchId} onChange={(e) => setBranchId(e.target.value)}>
                <option value="">All branches</option>
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
          )}
          {meta?.params.map((p) => (
            <Field key={p} label={p === "days" ? "Days" : p === "limit" ? "How many" : p}>
              <Input value={extra[p] ?? ""} onChange={(e) => setExtra((x) => ({ ...x, [p]: e.target.value }))} inputMode="numeric" className="w-24" />
            </Field>
          ))}
        </div>
      </Panel>
      <Panel padded={false}>
        {result.isPending ? (
          <TableSkeleton cols={5} />
        ) : result.isError ? (
          <ErrorState message={errorMessage(result.error)} onRetry={() => result.refetch()} />
        ) : !r || r.rows.length === 0 ? (
          <Empty title="Nothing for these filters" />
        ) : (
          <>
            {r.headings.length > 0 && <p className="border-b border-line px-4 py-2 text-xs text-faint">{r.headings.join(" · ")}</p>}
            <Table>
              <thead>
                <tr>
                  {r.columns.map((c) => (
                    <Th key={c.key} align={RIGHT.has(c.kind) ? "right" : undefined}>
                      {c.label}
                    </Th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {r.rows.map((row, i) => (
                  <tr key={i}>
                    {r.columns.map((c, j) => (
                      <Td key={c.key} align={RIGHT.has(c.kind) ? "right" : undefined}>
                        {cell(c.kind, row[j])}
                      </Td>
                    ))}
                  </tr>
                ))}
                {r.totals.length > 0 && (
                  <tr className="font-semibold">
                    {r.columns.map((c, j) => (
                      <Td key={c.key} align={RIGHT.has(c.kind) ? "right" : undefined}>
                        {j === 0 ? "Total" : r.totals[j] == null ? "" : cell(c.kind, r.totals[j])}
                      </Td>
                    ))}
                  </tr>
                )}
              </tbody>
            </Table>
            {r.truncated && <p className="border-t border-line px-4 py-3 text-sm text-warn">Showing the first {r.rows.length.toLocaleString()} of {r.rowCount.toLocaleString()} rows. Export to get them all.</p>}
          </>
        )}
      </Panel>
    </>
  );
}
