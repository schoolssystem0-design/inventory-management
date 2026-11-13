"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, Download, Lock, PackagePlus } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { Status } from "@/components/status";
import { Button, LinkButton } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Textarea } from "@/components/ui/fields";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { date, dateTime, money, qty } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

export default function PurchaseOrderPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const client = useQueryClient();
  const po = useQuery({ queryKey: ["po", id], queryFn: () => api<Schemas["PoView"]>(`/purchase-orders/${id}`) });
  const [action, setAction] = React.useState<"cancel" | "close" | null>(null);
  const [reason, setReason] = React.useState("");
  const act = useMutation({
    mutationFn: () => api(`/purchase-orders/${id}/${action}`, { method: "POST", body: { reason } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["po", id] });
      setAction(null);
      setReason("");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (po.isPending) return <Skeleton className="h-96" />;
  if (po.isError) return <ErrorState message={errorMessage(po.error)} onRetry={() => po.refetch()} />;
  const p = po.data;
  const open = p.status === "ORDERED" || p.status === "PARTIAL";
  return (
    <>
      <PageHeader
        back={{ href: toHref("/purchases"), label: "Purchasing" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{p.number}</span> <Status value={p.status} />
          </span>
        }
        description={`${p.supplierName} · ${p.branchName} · ${dateTime(p.at)}`}
        actions={
          <>
            {open && can(P.PURCHASING_MANAGE) && (
              <LinkButton href={toHref(`/purchases/receive?po=${p.id}`)}>
                <PackagePlus /> Receive goods
              </LinkButton>
            )}
            <Button
              variant="secondary"
              onClick={async () => {
                try {
                  saveBlob(await apiBlob(`/purchase-orders/${p.id}/pdf`), `${p.number}.pdf`);
                }
                catch (e) {
                  toast.error(errorMessage(e));
                }
              }}
            >
              <Download /> PDF
            </Button>
            {open && can(P.PURCHASING_MANAGE) && (
              <>
                {p.status === "PARTIAL" && (
                  <Button variant="ghost" onClick={() => setAction("close")}>
                    <Lock /> Close (rest not coming)
                  </Button>
                )}
                {p.status === "ORDERED" && (
                  <Button variant="danger" onClick={() => setAction("cancel")}>
                    <Ban /> Cancel
                  </Button>
                )}
              </>
            )}
          </>
        }
      />
      <Panel padded={false} title="Items">
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Ordered</Th>
              <Th align="right">Received</Th>
              <Th align="right">Still to come</Th>
              <Th align="right">Cost</Th>
              <Th align="right">Amount</Th>
            </tr>
          </thead>
          <tbody>
            {p.lines.map((l) => (
              <tr key={l.id}>
                <Td>
                  {l.productName}
                  <span className="block text-xs text-faint">{l.sku}</span>
                </Td>
                <Td align="right">
                  {qty(l.qtyOrdered)} {l.unitName}
                </Td>
                <Td align="right">{qty(l.qtyReceived)}</Td>
                <Td align="right">{qty(l.qtyOpen)}</Td>
                <Td align="right">{money(l.unitCost, false)}</Td>
                <Td align="right">{money(l.amount, false)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <p className="border-t border-line px-4 py-3 text-right text-sm">
          Total <span className="font-mono text-lg font-semibold">{money(p.total)}</span>
        </p>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Expected on", value: date(p.expectedOn) },
            { label: "Ordered by", value: p.actorName },
            { label: "Note", value: p.note },
            { label: "Closed because", value: p.closedReason },
          ]}
        />
      </Panel>
      {action && (
        <Dialog
          open
          onOpenChange={(o) => !o && setAction(null)}
          title={action === "cancel" ? `Cancel ${p.number}?` : `Close ${p.number}?`}
          description={action === "close" ? "What has come stays; the rest is no longer expected." : "Nothing has been received on this order."}
          footer={
            <Button variant={action === "cancel" ? "danger" : "primary"} onClick={() => act.mutate()} loading={act.isPending} disabled={!reason.trim()}>
              {action === "cancel" ? "Cancel order" : "Close order"}
            </Button>
          }
        >
          <Field label="Reason" required>
            <Textarea value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          </Field>
        </Dialog>
      )}
    </>
  );
}
