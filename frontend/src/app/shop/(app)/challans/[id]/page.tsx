"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileCheck2, Printer, Truck, XCircle } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { ArrangeDelivery } from "@/features/documents/arrange-delivery";
import { docHtml } from "@/features/documents/print-doc";
import { useShopHeader, useTillSession } from "@/features/documents/till";
import { printHtml } from "@/features/receipts/receipt";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, money, qty } from "@/lib/format";
import { isMoney } from "@/lib/money";
import { P, ShopLink, useCan, useFeature, useShopHref } from "@/lib/shop-context";

type Challan = Schemas["ChallanView"];

export default function ChallanPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const header = useShopHeader();
  const [dialog, setDialog] = React.useState<"bill" | "cancel" | "delivery" | null>(null);
  const challan = useQuery({ queryKey: ["challan", id], queryFn: () => api<Challan>(`/challans/${id}`) });
  const deliveries = useQuery({
    queryKey: ["deliveries", "challan", id],
    queryFn: () => api<{ items: Schemas["DeliveryView"][] }>(`/deliveries?challanId=${id}`),
    select: (d) => d.items,
  });
  if (challan.isPending) return <Skeleton className="h-96" />;
  if (challan.isError) return <ErrorState message={errorMessage(challan.error)} onRetry={() => challan.refetch()} />;
  const c = challan.data;
  const open = c.status === "OPEN";
  const print = () =>
    void printHtml(
      docHtml({
        shopName: header.name,
        shopLines: header.lines,
        title: "Delivery challan",
        number: c.number,
        date: date(c.date),
        sections: [
          {
            kind: "fields",
            rows: [
              ["Customer", c.customerName],
              ["Phone", c.customerPhone],
              ["Deliver to", c.address],
              ["Note", c.note],
            ],
          },
          { kind: "table", head: ["#", "Item", "Qty", "Unit"], right: [2], rows: c.lines.map((l) => [l.lineNo, l.productName, qty(l.qty), l.unitName ?? ""]) },
          { kind: "text", text: "Goods received in good condition. The bill follows." },
          { kind: "signatures", labels: ["Received by (name and signature)", "Driver", "For the shop"] },
        ],
      }),
    );
  return (
    <>
      <PageHeader
        back={{ href: toHref("/challans"), label: "Delivery challans" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{c.number}</span> <Status value={c.status} />
          </span>
        }
        description={`${c.customerName} · ${c.branchName} · ${date(c.date)}`}
        actions={
          <>
            <Button variant="secondary" onClick={print}>
              <Printer /> Print challan
            </Button>
            {open && can(P.DELIVERY_MANAGE) && (
              <Button variant="secondary" onClick={() => setDialog("delivery")}>
                <Truck /> Arrange delivery
              </Button>
            )}
            {open && can(P.POS_SELL) && (
              <Button onClick={() => setDialog("bill")}>
                <FileCheck2 /> Bill it
              </Button>
            )}
            {open && can(P.DELIVERY_MANAGE) && (
              <Button variant="ghost" onClick={() => setDialog("cancel")}>
                <XCircle /> Cancel
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Panel title="Goods sent" padded={false}>
          <Table>
            <thead>
              <tr>
                <Th>Item</Th>
                <Th align="right">Qty</Th>
                <Th align="right">Price</Th>
                <Th align="right">Amount</Th>
              </tr>
            </thead>
            <tbody>
              {c.lines.map((l) => (
                <tr key={l.id}>
                  <Td>
                    {l.productName} <span className="text-xs text-faint">{l.unitName}</span>
                  </Td>
                  <Td align="right">{qty(l.qty)}</Td>
                  <Td align="right">{money(l.price, false)}</Td>
                  <Td align="right">{money(l.amount, false)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <div className="flex justify-end border-t border-line px-5 py-3 font-semibold">Value {money(c.total)}</div>
        </Panel>
        <div className="space-y-4">
          <Panel title="Details">
            <DetailList
              items={[
                { label: "Customer", value: c.customerId ? <ShopLink href={`/customers/${c.customerId}`} className="text-aqua">{c.customerName}</ShopLink> : c.customerName },
                { label: "Phone", value: c.customerPhone },
                { label: "Deliver to", value: c.address },
                { label: "Note", value: c.note },
                { label: "Sent", value: `${dateTime(c.at)} by ${c.actorName}` },
                { label: "Invoice", value: c.saleId ? <ShopLink href={`/sales/${c.saleId}`} className="font-mono text-aqua">{c.saleNumber}</ShopLink> : null },
              ]}
            />
          </Panel>
          {!!deliveries.data?.length && (
            <Panel title="Deliveries" padded={false}>
              <ul className="divide-y divide-line text-sm">
                {deliveries.data.map((d) => (
                  <li key={d.id} className="flex items-center justify-between px-5 py-2.5">
                    <span className="font-mono">{d.number}</span>
                    <Status value={d.status} />
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      </div>
      {dialog === "bill" && <BillDialog challan={c} onClose={() => setDialog(null)} />}
      {dialog === "cancel" && <CancelDialog challan={c} onClose={() => setDialog(null)} />}
      {dialog === "delivery" && <ArrangeDelivery challanId={c.id} customerName={c.customerName} address={c.address} onClose={() => setDialog(null)} />}
    </>
  );
}

function BillDialog({ challan, onClose }: { challan: Challan; onClose: () => void }) {
  const client = useQueryClient();
  const approval = useApproval();
  const tillSessionId = useTillSession(challan.branchId);
  const feature = useFeature();
  const [method, setMethod] = React.useState(challan.customerId ? "ACCOUNT" : "CASH");
  const [amount, setAmount] = React.useState(challan.total);
  const bill = useMutation({
    mutationFn: () =>
      approval.run((headers) =>
        api<Challan>(`/challans/${challan.id}/convert`, {
          method: "POST",
          body: { tillSessionId, payments: method === "ACCOUNT" ? [{ method: "ACCOUNT" }] : [{ method, amount }] },
          headers: { "Idempotency-Key": crypto.randomUUID(), ...headers },
        }),
      ),
    onSuccess: (v) => {
      client.setQueryData(["challan", String(challan.id)], v);
      client.invalidateQueries({ queryKey: ["challans"] });
      toast.success(`Billed on invoice ${v.saleNumber}`);
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Bill ${challan.number}`}
      description={`An invoice of ${money(challan.total)} at the challan prices. Stock does not move again.`}
      footer={
        <Button onClick={() => bill.mutate()} loading={bill.isPending} disabled={!tillSessionId || (method !== "ACCOUNT" && !isMoney(amount))}>
          Make the invoice
        </Button>
      }
    >
      <div className="space-y-4">
        {!tillSessionId && <p className="rounded-xl bg-warn/10 p-3 text-sm text-warn">Open a till at this branch first.</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Paid by">
            <Select value={method} onChange={(e) => setMethod(e.target.value)}>
              {challan.customerId && <option value="ACCOUNT">On the customer&apos;s account</option>}
              <option value="CASH">Cash</option>
              <option value="BANK">Bank</option>
              <option value="CHEQUE">Cheque</option>
              <option value="JAZZCASH">JazzCash</option>
              <option value="EASYPAISA">Easypaisa</option>
            </Select>
          </Field>
          {method !== "ACCOUNT" && (
            <Field label="Amount">
              <Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" className="font-mono" />
            </Field>
          )}
        </div>
        {feature("installments") && challan.customerId && <p className="text-xs text-muted">To sell on installments, bill it on account and make a plan from the invoice.</p>}
        {bill.error && <p className="text-sm text-bad">{errorMessage(bill.error)}</p>}
      </div>
      {approval.dialog}
    </Dialog>
  );
}

function CancelDialog({ challan, onClose }: { challan: Challan; onClose: () => void }) {
  const client = useQueryClient();
  const [reason, setReason] = React.useState("");
  const cancel = useMutation({
    mutationFn: () => api<Challan>(`/challans/${challan.id}/cancel`, { method: "POST", body: { reason } }),
    onSuccess: (v) => {
      client.setQueryData(["challan", String(challan.id)], v);
      client.invalidateQueries({ queryKey: ["challans"] });
      toast.success("Challan cancelled — stock is back");
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Cancel ${challan.number}`}
      description="The goods come back into stock."
      footer={
        <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={!reason.trim()}>
          Cancel challan
        </Button>
      }
    >
      <Field label="Why" required>
        <Textarea value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </Field>
      {cancel.error && <p className="mt-2 text-sm text-bad">{errorMessage(cancel.error)}</p>}
    </Dialog>
  );
}
