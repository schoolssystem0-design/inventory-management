"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { Ban, CheckCheck, ScanLine } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { ScanButton } from "@/components/barcode-scanner";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/dialog";
import { Input, Select } from "@/components/ui/fields";
import { ErrorState, PageHeader, Panel, Skeleton, StatCard, Table, Td, Th } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, money, qty } from "@/lib/format";
import { fromMilli, isQty, toMilli } from "@/lib/money";
import { P, useCan, useShopHref } from "@/lib/shop-context";

type Count = Schemas["CountView"];
type Line = Schemas["CountLineView"];

const lineKey = (l: { productId: number; batchId?: number | null }) => `${l.productId}:${l.batchId ?? ""}`;

/** Count screen (STK-04): scan each item (adds 1) or type the counted quantity; saves as you go. */
export default function CountPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const client = useQueryClient();
  const approval = useApproval();
  const count = useQuery({ queryKey: ["count", id], queryFn: () => api<Count>(`/stock-counts/${id}`) });
  const [edits, setEdits] = React.useState<Record<string, string>>({});
  const [scan, setScan] = React.useState("");
  const [show, setShow] = React.useState<"all" | "uncounted" | "differences">("all");
  const [q, setQ] = React.useState("");
  const [confirm, setConfirm] = React.useState<"post" | "cancel" | null>(null);
  const [flash, setFlash] = React.useState<string | null>(null);
  const pending = React.useRef<Record<string, string>>({});
  const timer = React.useRef<number | null>(null);

  const save = useMutation({
    mutationFn: (entries: Schemas["CountEntry"][]) => api<Count>(`/stock-counts/${id}/lines`, { method: "PUT", body: { entries } }),
    onSuccess: (c) => client.setQueryData(["count", id], c),
    onError: (e) => toast.error(`Not saved: ${errorMessage(e)}`),
  });
  const [dirty, setDirty] = React.useState(false);
  /** Sends the counts typed or scanned since the last save; resolves when they are stored. */
  const flush = React.useCallback(async () => {
    if (timer.current) window.clearTimeout(timer.current);
    const entries = Object.entries(pending.current).map(([k, v]) => {
      const [productId, batchId] = k.split(":");
      return { productId: Number(productId), batchId: batchId ? Number(batchId) : undefined, countedQty: v };
    });
    pending.current = {};
    setDirty(false);
    if (entries.length) await save.mutateAsync(entries);
  }, [save]);
  const queue = (key: string, value: string) => {
    if (!isQty(value)) return;
    pending.current[key] = value;
    setDirty(true);
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void flush().catch(() => {}), 800);
  };
  React.useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
  }, []);

  const post = useMutation({
    mutationFn: async () => {
      await flush();
      return approval.run((headers) => api<Count>(`/stock-counts/${id}/post`, { method: "POST", headers }));
    },
    onSuccess: (c) => {
      client.setQueryData(["count", id], c);
      toast.success("Count posted — stock now matches what you counted");
      setConfirm(null);
    },
    onError: (e) => {
      if (!(e instanceof ApiError && e.code === "approval_required")) toast.error(errorMessage(e));
    },
  });
  const cancel = useMutation({
    mutationFn: () => api(`/stock-counts/${id}/cancel`, { method: "POST" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["count", id] });
      setConfirm(null);
    },
  });

  if (count.isPending) return <Skeleton className="h-96" />;
  if (count.isError) return <ErrorState message={errorMessage(count.error)} onRetry={() => count.refetch()} />;
  const c = count.data;
  const open = c.status === "OPEN" && can(P.STOCK_COUNT);
  const value = (l: Line) => edits[lineKey(l)] ?? (l.countedQty == null ? "" : trim(l.countedQty));

  function scanned(code: string) {
    const text = code.trim().toLowerCase();
    if (!text) return;
    const matches = c.items.filter((l) => l.barcode?.toLowerCase() === text || l.sku.toLowerCase() === text);
    if (matches.length === 0) {
      toast.error(`${code} is not in this count`);
      return;
    }
    const l = matches[0];
    const key = lineKey(l);
    const next = fromMilli(toMilli(value(l) || "0") + BigInt(1000));
    const shown = trim(next);
    setEdits((e) => ({ ...e, [key]: shown }));
    queue(key, shown);
    setFlash(key);
    window.setTimeout(() => setFlash((f) => (f === key ? null : f)), 600);
    setScan("");
  }

  const items = c.items.filter((l) => {
    if (q && !`${l.productName} ${l.sku} ${l.barcode ?? ""}`.toLowerCase().includes(q.toLowerCase())) return false;
    if (show === "uncounted") return value(l) === "";
    if (show === "differences") return l.difference != null && toMilli(l.difference) !== BigInt(0);
    return true;
  });

  return (
    <>
      <PageHeader
        back={{ href: toHref("/stock/counts"), label: "Stock counts" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{c.number}</span> <Status value={c.status} />
          </span>
        }
        description={`${c.scope} · ${c.branchName} · started ${dateTime(c.startedAt)}`}
        actions={
          open && (
            <>
              <Button variant="danger" onClick={() => setConfirm("cancel")}>
                <Ban /> Cancel
              </Button>
              <Button onClick={() => setConfirm("post")} disabled={save.isPending}>
                <CheckCheck /> Post count
              </Button>
            </>
          )
        }
      />
      <div className="mb-4 grid gap-3 sm:grid-cols-4">
        <StatCard label="Counted" value={`${c.counted} / ${c.lines}`} tone="aqua" />
        <StatCard label="Short (value)" value={money(c.lossValue)} tone={c.lossValue !== "0.00" ? "bad" : undefined} />
        <StatCard label="Over (value)" value={money(c.gainValue)} tone={c.gainValue !== "0.00" ? "good" : undefined} />
        <StatCard label="Your counts" value={save.isPending || dirty ? "Saving…" : "Saved"} />
      </div>
      {open && (
        <Panel className="mb-4">
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              scanned(scan);
            }}
          >
            <div className="relative flex-1">
              <ScanLine className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-faint" aria-hidden />
              <Input value={scan} onChange={(e) => setScan(e.target.value)} placeholder="Scan an item — each scan adds 1" className="h-12 pl-10 text-lg" autoFocus aria-label="Scan to count" />
            </div>
            <ScanButton onCode={scanned} />
          </form>
        </Panel>
      )}
      <Panel padded={false}>
        <div className="flex flex-wrap gap-2 border-b border-line p-3">
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Find in this count" className="max-w-xs" aria-label="Find in this count" />
          <Select value={show} onChange={(e) => setShow(e.target.value as typeof show)} className="w-auto" aria-label="Show">
            <option value="all">All lines</option>
            <option value="uncounted">Not counted yet</option>
            <option value="differences">With a difference</option>
          </Select>
        </div>
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">System</Th>
              <Th align="right">Counted</Th>
              <Th align="right">Difference</Th>
              {can(P.COST_VIEW) && <Th align="right">Value</Th>}
            </tr>
          </thead>
          <tbody>
            {items.map((l) => {
              const key = lineKey(l);
              return (
                <tr key={key} className={cn(flash === key && "bg-aqua/10")}>
                  <Td>
                    {l.productName}
                    <span className="block text-xs text-faint">
                      {l.sku}
                      {l.batchNo ? ` · Batch ${l.batchNo}` : ""}
                    </span>
                  </Td>
                  <Td align="right">{qty(l.expectedQty ?? l.systemQty)}</Td>
                  <Td align="right">
                    {open ? (
                      <Input
                        value={value(l)}
                        onChange={(e) => {
                          setEdits((s) => ({ ...s, [key]: e.target.value }));
                          queue(key, e.target.value);
                        }}
                        onBlur={() => void flush().catch(() => {})}
                        inputMode="decimal"
                        className={cn("ml-auto w-24 text-right font-mono", value(l) && !isQty(value(l)) && "border-bad")}
                        aria-label={`Counted ${l.productName}`}
                      />
                    ) : (
                      qty(l.countedQty)
                    )}
                  </Td>
                  <Td align="right" className={l.difference?.startsWith("-") ? "text-bad" : l.difference && toMilli(l.difference) > BigInt(0) ? "text-good" : undefined}>
                    {l.difference == null ? "—" : qty(l.difference)}
                  </Td>
                  {can(P.COST_VIEW) && <Td align="right">{l.differenceValue ? money(l.differenceValue, false) : "—"}</Td>}
                </tr>
              );
            })}
          </tbody>
        </Table>
      </Panel>
      <ConfirmDialog
        open={confirm === "post"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Post this count?"
        description={`Stock is set to what you counted. Lines not counted are left as they are. Short ${money(c.lossValue)}, over ${money(c.gainValue)}.`}
        confirmLabel="Post count"
        loading={post.isPending}
        onConfirm={() => post.mutate()}
      />
      <ConfirmDialog
        open={confirm === "cancel"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Cancel this count?"
        description="Nothing changes in stock."
        confirmLabel="Cancel count"
        danger
        loading={cancel.isPending}
        onConfirm={() => cancel.mutate()}
      />
      {approval.dialog}
    </>
  );
}

function trim(q: string) {
  return q.includes(".") ? q.replace(/0+$/, "").replace(/\.$/, "") : q;
}
