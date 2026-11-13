"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Tabs } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, label } from "@/lib/format";
import { P, ShopLink, useCan, useShop } from "@/lib/shop-context";

type Row = Schemas["AlertView"];
const KINDS = ["LOW", "OUT", "NEAR_EXPIRY", "EXPIRED", "DEAD"] as const;

/** Stock alerts (STK-08): low, out, near expiry, expired and items not selling. */
export default function AlertsPage() {
  const can = useCan();
  const shop = useShop();
  const client = useQueryClient();
  const [kind, setKind] = React.useState<(typeof KINDS)[number] | "">("");
  const summary = useQuery({ queryKey: ["alerts-summary"], queryFn: () => api<Schemas["AlertSummary"]>("/alerts/summary") });
  const refresh = useMutation({
    mutationFn: () => api("/alerts/refresh", { method: "POST" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["alerts"] });
      client.invalidateQueries({ queryKey: ["alerts-summary"] });
      toast.success("Alerts checked again");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const seen = useMutation({
    mutationFn: (id: number) => api(`/alerts/${id}/seen`, { method: "POST" }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["alerts"] }),
  });
  const open = (summary.data?.open ?? {}) as Record<string, number>;
  const columns: Column<Row>[] = [
    { label: "Alert", render: (a) => <Status value={a.kind} /> },
    {
      label: "Product",
      render: (a) => (
        <ShopLink href={`/stock/movements?productId=${a.productId}`} className="hover:text-aqua">
          {a.productName}
          {a.batchNo && <span className="block text-xs text-faint">Batch {a.batchNo}</span>}
        </ShopLink>
      ),
    },
    { label: "Details", render: (a) => <span className="text-muted">{a.message}</span> },
    { label: "Branch", render: (a) => <span className="text-muted">{a.branchName}</span>, hidden: shop.branches.length < 2 },
    { label: "Since", render: (a) => <span className="text-muted">{dateTime(a.createdAt)}</span> },
    {
      label: "Seen",
      render: (a) =>
        a.seenAt ? (
          <span className="text-xs text-faint">Seen</span>
        ) : (
          <button className="text-sm font-semibold text-aqua" onClick={() => seen.mutate(a.id)}>
            Mark seen
          </button>
        ),
    },
  ];
  return (
    <ListPage<Row>
      key={kind}
      title="Stock alerts"
      description="Checked every night and after each sale and delivery."
      path="/alerts"
      queryKey="alerts"
      columns={columns}
      extraParams={{ kind }}
      actions={
        can(P.STOCK_VIEW) && (
          <Button variant="secondary" onClick={() => refresh.mutate()} loading={refresh.isPending}>
            <RefreshCw /> Check now
          </Button>
        )
      }
      filters={branchFilter(shop.branches)}
      empty={{ title: "No alerts", description: "Nothing is low, out, expiring or stuck." }}
    >
      <Tabs
        value={kind}
        onChange={setKind}
        tabs={[{ value: "" as const, label: "All" }, ...KINDS.map((k) => ({ value: k, label: `${label(k)}${open[k] ? ` (${open[k]})` : ""}` }))]}
      />
    </ListPage>
  );
}
