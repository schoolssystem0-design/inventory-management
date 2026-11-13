"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { ChefHat, Printer } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/fields";
import { Badge, ErrorState, PageHeader, Skeleton } from "@/components/ui/layout";
import { docHtml } from "@/features/documents/print-doc";
import { useShopHeader } from "@/features/documents/till";
import { printHtml } from "@/features/receipts/receipt";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { label } from "@/lib/format";
import { useBranch, useShop } from "@/lib/shop-context";

type Ticket = Schemas["TicketView"];

const COLUMNS: { status: string; title: string; next: string; action: string }[] = [
  { status: "NEW", title: "New", next: "PREPARING", action: "Start" },
  { status: "PREPARING", title: "Preparing", next: "READY", action: "Ready" },
  { status: "READY", title: "Ready to collect", next: "SERVED", action: "Collected" },
];

/** Kitchen screen (fast food): today's orders by stage. Refreshes by itself every few seconds. */
export default function KitchenPage() {
  const shop = useShop();
  const [branch, setBranch] = useBranch();
  const branchId = branch?.id ?? null;
  const tickets = useQuery({
    queryKey: ["kitchen", branchId],
    queryFn: () => api<Ticket[]>(`/kitchen/tickets${branchId ? `?branchId=${branchId}` : ""}`),
    refetchInterval: 5000,
    refetchIntervalInBackground: true,
  });
  return (
    <>
      <PageHeader
        title="Kitchen"
        description="New orders appear here by themselves. Tap to move an order on; the counter calls the token when it is ready."
        actions={
          shop.branches.length > 1 && (
            <Select value={branchId ?? ""} onChange={(e) => setBranch(Number(e.target.value))} aria-label="Branch" className="w-auto">
              {shop.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </Select>
          )
        }
      />
      {tickets.isPending ? (
        <Skeleton className="h-96" />
      ) : tickets.isError ? (
        <ErrorState message={errorMessage(tickets.error)} onRetry={() => tickets.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          {COLUMNS.map((c) => {
            const list = tickets.data.filter((t) => t.status === c.status);
            return (
              <section key={c.status} aria-label={c.title} className="min-w-0">
                <h2 className="mb-2 flex items-center gap-2 text-sm font-semibold text-muted">
                  {c.title} <Badge tone={c.status === "READY" ? "good" : c.status === "PREPARING" ? "info" : "aqua"}>{list.length}</Badge>
                </h2>
                <ul className="space-y-3">
                  {list.length === 0 && <li className="rounded-xl border border-dashed border-line p-6 text-center text-sm text-faint">Nothing here</li>}
                  {list.map((t) => (
                    <TicketCard key={t.id} ticket={t} column={c} />
                  ))}
                </ul>
              </section>
            );
          })}
        </div>
      )}
    </>
  );
}

function TicketCard({ ticket: t, column }: { ticket: Ticket; column: (typeof COLUMNS)[number] }) {
  const client = useQueryClient();
  const header = useShopHeader();
  const move = useMutation({
    mutationFn: () => api<Ticket>(`/kitchen/tickets/${t.id}/status`, { method: "POST", body: { status: column.next } }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["kitchen"] }),
    onError: (e) => {
      toast.error(errorMessage(e));
      client.invalidateQueries({ queryKey: ["kitchen"] });
    },
  });
  const late = t.status !== "READY" && t.minutes >= 15;
  return (
    <li className={cn("tile p-4", late && "ring-2 ring-bad/60")} aria-label={`Token ${t.tokenNo}`}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-mono text-3xl font-bold text-aqua">#{t.tokenNo}</p>
          <p className="text-sm text-muted">
            {label(t.orderType)}
            {t.tableNo ? ` · Table ${t.tableNo}` : ""}
            {t.customerName ? ` · ${t.customerName}` : ""}
          </p>
        </div>
        <span className={cn("text-xs", late ? "font-semibold text-bad" : "text-faint")}>{t.minutes} min</span>
      </div>
      <ul className="mt-3 space-y-1.5">
        {t.items.map((i, k) => (
          <li key={k} className="text-sm">
            <span className="font-semibold">{i.qty} ×</span> {i.name}
            {i.contains.length > 0 && <span className="block pl-5 text-xs text-muted">{i.contains.join(", ")}</span>}
            {i.note && <span className="block pl-5 text-xs font-medium text-warn">{i.note}</span>}
          </li>
        ))}
      </ul>
      <div className="mt-4 flex gap-2">
        <Button className="flex-1" size="lg" onClick={() => move.mutate()} loading={move.isPending}>
          {column.status === "NEW" && <ChefHat />} {column.action}
        </Button>
        <Button
          variant="secondary"
          size="lg"
          aria-label={`Print ticket ${t.tokenNo}`}
          onClick={() =>
            void printHtml(
              docHtml({
                narrow: true,
                shopName: header.name,
                title: "Kitchen ticket",
                number: `Token #${t.tokenNo}`,
                date: `${label(t.orderType)}${t.tableNo ? ` · Table ${t.tableNo}` : ""} · ${new Date(t.createdAt).toLocaleTimeString()}`,
                sections: [{ kind: "table", head: ["Qty", "Item"], rows: t.items.map((i) => [i.qty, [i.name, i.contains.join(", "), i.note ? `** ${i.note}` : ""].filter(Boolean).join(" — ")]) }],
              }),
            )
          }
        >
          <Printer />
        </Button>
      </div>
    </li>
  );
}
