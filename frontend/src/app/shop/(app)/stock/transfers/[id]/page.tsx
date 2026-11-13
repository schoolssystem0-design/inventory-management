"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PackageCheck } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/fields";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, qty } from "@/lib/format";
import { isQty } from "@/lib/money";
import { P, useCan, useShop, useShopHref } from "@/lib/shop-context";

export default function TransferPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const shop = useShop();
  const toHref = useShopHref();
  const client = useQueryClient();
  const t = useQuery({ queryKey: ["transfer", id], queryFn: () => api<Schemas["TransferView"]>(`/transfers/${id}`) });
  const [received, setReceived] = React.useState<Record<number, string>>({});
  const receive = useMutation({
    mutationFn: (lines: { lineId: number; qtyReceived: string }[]) => api<Schemas["TransferView"]>(`/transfers/${id}/receive`, { method: "POST", body: { lines } }),
    onSuccess: (v) => {
      client.setQueryData(["transfer", id], v);
      toast.success("Received into stock");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (t.isPending) return <Skeleton className="h-96" />;
  if (t.isError) return <ErrorState message={errorMessage(t.error)} onRetry={() => t.refetch()} />;
  const v = t.data;
  const canReceive = v.status === "IN_TRANSIT" && can(P.STOCK_TRANSFER) && (shop.user.allBranches || shop.branches.some((b) => b.id === v.toBranchId));
  const value = (l: Schemas["TransferLineView"]) => received[l.id] ?? trim(l.qtySent);
  return (
    <>
      <PageHeader
        back={{ href: toHref("/stock/transfers"), label: "Transfers" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{v.number}</span> <Status value={v.status} />
          </span>
        }
        description={`${v.fromBranchName} → ${v.toBranchName}`}
        actions={
          canReceive && (
            <Button
              onClick={() => receive.mutate(v.lines.map((l) => ({ lineId: l.id, qtyReceived: value(l) })))}
              loading={receive.isPending}
              disabled={v.lines.some((l) => !isQty(value(l)))}
            >
              <PackageCheck /> Receive
            </Button>
          )
        }
      />
      <Panel padded={false} title="Items" description={canReceive ? "Change a quantity if less arrived; the shortfall is recorded." : undefined}>
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Sent</Th>
              <Th align="right">Received</Th>
            </tr>
          </thead>
          <tbody>
            {v.lines.map((l) => (
              <tr key={l.id}>
                <Td>
                  {l.productName}
                  <span className="block text-xs text-faint">{[l.batchNo && `Batch ${l.batchNo}`, l.serial].filter(Boolean).join(" · ")}</span>
                </Td>
                <Td align="right">{qty(l.qtySent)}</Td>
                <Td align="right">
                  {canReceive ? (
                    <Input
                      value={value(l)}
                      onChange={(e) => setReceived((r) => ({ ...r, [l.id]: e.target.value }))}
                      className="ml-auto w-24 text-right font-mono"
                      inputMode="decimal"
                      disabled={Boolean(l.serial)}
                      aria-label={`Received ${l.productName}`}
                    />
                  ) : (
                    qty(l.qtyReceived)
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Sent", value: v.dispatchedAt ? `${dateTime(v.dispatchedAt)} by ${v.dispatchedBy}` : null },
            { label: "Received", value: v.receivedAt ? `${dateTime(v.receivedAt)} by ${v.receivedBy}` : null },
            { label: "Short", value: v.shortQty && v.shortQty !== "0" && v.shortQty !== "0.000" ? qty(v.shortQty) : null },
            { label: "Note", value: v.note },
          ]}
        />
      </Panel>
    </>
  );
}

function trim(q: string) {
  return q.includes(".") ? q.replace(/0+$/, "").replace(/\.$/, "") : q;
}
