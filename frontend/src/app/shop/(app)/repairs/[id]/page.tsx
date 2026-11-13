"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { HandCoins, Printer, Trash2, XCircle } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { ProductPicker } from "@/components/product-picker";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { DetailList, Empty, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { docHtml } from "@/features/documents/print-doc";
import { useShopHeader, useTillSession } from "@/features/documents/till";
import { printHtml } from "@/features/receipts/receipt";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, label, money, qty } from "@/lib/format";
import { cmpMoney, isMoney, ZERO } from "@/lib/money";
import { P, ShopLink, useCan, useShopHref } from "@/lib/shop-context";

type Job = Schemas["RepairView"];
const OPEN = ["RECEIVED", "CHECKING", "WAITING_PARTS", "REPAIRING", "READY", "NOT_REPAIRABLE"];
const NEXT = ["RECEIVED", "CHECKING", "WAITING_PARTS", "REPAIRING", "READY", "NOT_REPAIRABLE"];

export default function RepairPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const header = useShopHeader();
  const [dialog, setDialog] = React.useState<"deliver" | "cancel" | null>(null);
  const job = useQuery({ queryKey: ["repair", id], queryFn: () => api<Job>(`/repairs/${id}`) });
  if (job.isPending) return <Skeleton className="h-96" />;
  if (job.isError) return <ErrorState message={errorMessage(job.error)} onRetry={() => job.refetch()} />;
  const j = job.data;
  const open = OPEN.includes(j.status);
  const manage = can(P.REPAIRS_MANAGE) && open;
  const print = () =>
    void printHtml(
      docHtml({
        shopName: header.name,
        shopLines: header.lines,
        title: "Repair job card",
        number: j.number,
        date: dateTime(j.at),
        sections: [
          {
            kind: "fields",
            rows: [
              ["Customer", j.customerName],
              ["Phone", j.customerPhone],
              ["Device", j.device],
              ["IMEI / serial", j.serial],
              ["Fault", j.problem],
              ["Left with it", j.accessories],
              ["Condition", j.conditionNote],
              ["Estimate", j.estimate ? money(j.estimate) : null],
              ["Advance paid", money(j.advance)],
              ["Promised for", j.promisedOn ? date(j.promisedOn) : null],
              ["Status", label(j.status)],
            ],
          },
          ...(j.parts.length || cmpMoney(j.labour, ZERO) > 0
            ? [
                {
                  kind: "table" as const,
                  title: "Charges",
                  head: ["Item", "Qty", "Price", "Amount"],
                  right: [1, 2, 3],
                  rows: [...j.parts.map((p) => [p.productName, qty(p.qty), money(p.price, false), money(p.amount, false)]), ...(cmpMoney(j.labour, ZERO) > 0 ? [["Labour", "", "", money(j.labour, false)]] : [])],
                  foot: ["Total", "", "", money(j.total, false)],
                },
              ]
            : []),
          { kind: "text", title: "Terms", text: "Bring this card to collect the device. Devices not collected within 30 days of the ready message are at the owner's risk. Data on the device is the customer's responsibility." },
          { kind: "signatures", labels: ["Customer", "Received by"] },
        ],
      }),
    );
  return (
    <>
      <PageHeader
        back={{ href: toHref("/repairs"), label: "Repair jobs" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{j.number}</span> <Status value={j.status} />
          </span>
        }
        description={`${j.device} · ${j.customerName}${j.customerPhone ? ` · ${j.customerPhone}` : ""}`}
        actions={
          <>
            <Button variant="secondary" onClick={print}>
              <Printer /> Job card
            </Button>
            {can(P.POS_SELL) && (j.status === "READY" || j.status === "NOT_REPAIRABLE") && (
              <Button onClick={() => setDialog("deliver")}>
                <HandCoins /> Hand back
              </Button>
            )}
            {manage && (
              <Button variant="ghost" onClick={() => setDialog("cancel")}>
                <XCircle /> Cancel job
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <div className="space-y-4">
          <Panel title="Job">
            <DetailList
              items={[
                { label: "Fault", value: j.problem },
                { label: "IMEI / serial", value: j.serial ? <span className="font-mono">{j.serial}</span> : null },
                { label: "Left with it", value: j.accessories },
                { label: "Condition", value: j.conditionNote },
                { label: "Diagnosis", value: j.diagnosis },
                { label: "Technician", value: j.technicianName },
                { label: "Promised for", value: j.promisedOn ? date(j.promisedOn) : null },
                { label: "Booked", value: `${dateTime(j.at)} by ${j.actorName}` },
                { label: "Invoice", value: j.saleId ? <ShopLink href={`/sales/${j.saleId}`} className="font-mono text-aqua">{j.saleNumber}</ShopLink> : null },
                { label: "Ready SMS", value: j.readySmsSent ? "Sent" : j.status === "READY" ? "Not sent (no phone or SMS off)" : null },
              ]}
            />
          </Panel>
          <Parts job={j} editable={manage} />
          <Panel title="History" padded={false}>
            <ol className="divide-y divide-line">
              {j.events.map((e, i) => (
                <li key={i} className="flex items-start justify-between gap-3 px-5 py-2.5 text-sm">
                  <span>
                    <Status value={e.status} /> {e.note && <span className="ml-2 text-muted">{e.note}</span>}
                  </span>
                  <span className="shrink-0 text-xs text-faint">
                    {e.actorName} · {dateTime(e.at)}
                  </span>
                </li>
              ))}
            </ol>
          </Panel>
        </div>
        <div className="space-y-4">
          <Panel title="Money">
            <dl className="space-y-1.5 text-sm">
              <Row label="Parts" value={money(j.partsTotal)} />
              <Row label="Labour" value={money(j.labour)} />
              <Row label="Total" value={money(j.total)} strong />
              <Row label="Advance paid" value={`− ${money(j.advance)}`} />
              <Row label="To collect" value={money(j.balance)} strong />
              {j.estimate && <Row label="Estimate given" value={money(j.estimate)} />}
            </dl>
          </Panel>
          {manage && <UpdateJob job={j} />}
        </div>
      </div>
      {dialog === "deliver" && <DeliverDialog job={j} onClose={() => setDialog(null)} />}
      {dialog === "cancel" && <CancelDialog job={j} onClose={() => setDialog(null)} />}
    </>
  );
}

function Row({ label: l, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={`flex justify-between ${strong ? "font-semibold" : ""}`}>
      <dt className="text-muted">{l}</dt>
      <dd className="font-mono">{value}</dd>
    </div>
  );
}

function Parts({ job, editable }: { job: Job; editable: boolean }) {
  const client = useQueryClient();
  const done = (v: Job) => client.setQueryData(["repair", String(job.id)], v);
  const add = useMutation({
    mutationFn: (productId: number) => api<Job>(`/repairs/${job.id}/parts`, { method: "POST", body: { productId, qty: "1" } }),
    onSuccess: done,
    onError: (e) => toast.error(errorMessage(e)),
  });
  const remove = useMutation({
    mutationFn: (partId: number) => api<Job>(`/repairs/${job.id}/parts/${partId}`, { method: "DELETE" }),
    onSuccess: done,
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Parts used" description={editable ? "Parts leave stock when the device is handed back." : undefined} padded={false}>
      {editable && (
        <div className="border-b border-line p-4">
          <ProductPicker placeholder="Add a part (scan or type)" kinds={["STANDARD", "BATCH"]} onPick={(p) => add.mutate(p.id)} />
        </div>
      )}
      {job.parts.length === 0 ? (
        <Empty title="No parts" />
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Part</Th>
              <Th align="right">Qty</Th>
              <Th align="right">Price</Th>
              <Th align="right">Amount</Th>
              {editable && <Th />}
            </tr>
          </thead>
          <tbody>
            {job.parts.map((p) => (
              <tr key={p.id}>
                <Td>
                  {p.productName} <span className="text-xs text-faint">{p.sku}</span>
                </Td>
                <Td align="right">{qty(p.qty)}</Td>
                <Td align="right">{money(p.price, false)}</Td>
                <Td align="right">{money(p.amount, false)}</Td>
                {editable && (
                  <Td align="right">
                    <Button size="sm" variant="ghost" aria-label={`Remove ${p.productName}`} onClick={() => remove.mutate(p.id)}>
                      <Trash2 />
                    </Button>
                  </Td>
                )}
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}

function UpdateJob({ job }: { job: Job }) {
  const client = useQueryClient();
  const [status, setStatus] = React.useState(job.status);
  const [diagnosis, setDiagnosis] = React.useState(job.diagnosis ?? "");
  const [labour, setLabour] = React.useState(job.labour === "0.00" ? "" : job.labour);
  const [note, setNote] = React.useState("");
  const save = useMutation({
    mutationFn: () =>
      api<Job>(`/repairs/${job.id}`, {
        method: "PATCH",
        body: { status, diagnosis: diagnosis || null, labour: labour || "0", note: note || null },
      }),
    onSuccess: (v) => {
      client.setQueryData(["repair", String(job.id)], v);
      client.invalidateQueries({ queryKey: ["repairs"] });
      setNote("");
      toast.success(v.status === "READY" && v.readySmsSent ? "Saved — the customer was sent an SMS" : "Saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Update">
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Status">
          <Select value={status} onChange={(e) => setStatus(e.target.value)}>
            {NEXT.map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Diagnosis">
          <Textarea value={diagnosis} onChange={(e) => setDiagnosis(e.target.value)} rows={2} />
        </Field>
        <Field label="Labour (Rs)">
          <Input value={labour} onChange={(e) => setLabour(e.target.value)} inputMode="decimal" />
        </Field>
        <Field label="Note for the history">
          <Input value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <Button type="submit" loading={save.isPending} disabled={!!labour && !isMoney(labour)} className="w-full">
          Save
        </Button>
        {status === "READY" && job.status !== "READY" && <p className="text-xs text-muted">The customer gets an SMS that it is ready (when SMS is on).</p>}
      </form>
    </Panel>
  );
}

function DeliverDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const client = useQueryClient();
  const approval = useApproval();
  const tillSessionId = useTillSession(job.branchId);
  const [method, setMethod] = React.useState(job.customerId ? "CASH" : "CASH");
  const [amount, setAmount] = React.useState(job.balance);
  const nothing = cmpMoney(job.total, ZERO) === 0;
  const deliver = useMutation({
    mutationFn: () =>
      approval.run((headers) =>
        api<Job>(`/repairs/${job.id}/deliver`, {
          method: "POST",
          body: { tillSessionId, payments: nothing || method === "ACCOUNT" ? [{ method: "ACCOUNT" }] : [{ method, amount }] },
          headers: { "Idempotency-Key": crypto.randomUUID(), ...headers },
        }),
      ),
    onSuccess: (v) => {
      client.setQueryData(["repair", String(job.id)], v);
      client.invalidateQueries({ queryKey: ["repairs"] });
      client.invalidateQueries({ queryKey: ["repair-counts"] });
      toast.success(v.saleNumber ? `Handed back · invoice ${v.saleNumber}` : "Handed back");
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Hand back ${job.device}`}
      description={nothing ? "Nothing to charge." : `Total ${money(job.total)}, advance ${money(job.advance)}. An invoice is made for the parts and labour.`}
      footer={
        <Button onClick={() => deliver.mutate()} loading={deliver.isPending} disabled={!tillSessionId}>
          {nothing ? "Hand back" : `Collect ${money(method === "ACCOUNT" ? ZERO : amount)} and hand back`}
        </Button>
      }
    >
      <div className="space-y-4">
        {!tillSessionId && <p className="rounded-xl bg-warn/10 p-3 text-sm text-warn">Open your till first: the money goes into it.</p>}
        {!nothing && cmpMoney(job.balance, ZERO) > 0 && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Paid by">
              <Select value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="CASH">Cash</option>
                <option value="CARD">Card</option>
                <option value="JAZZCASH">JazzCash</option>
                <option value="EASYPAISA">Easypaisa</option>
                <option value="BANK">Bank</option>
                {job.customerId && <option value="ACCOUNT">On the customer&apos;s account</option>}
              </Select>
            </Field>
            {method !== "ACCOUNT" && (
              <Field label="Amount">
                <Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" className="font-mono" />
              </Field>
            )}
          </div>
        )}
        {cmpMoney(job.advance, job.total) > 0 && <p className="text-sm text-muted">{money(String(Number(job.advance) - Number(job.total)))} of the advance is given back in cash.</p>}
        {deliver.error && <p className="text-sm text-bad">{errorMessage(deliver.error)}</p>}
      </div>
      {approval.dialog}
    </Dialog>
  );
}

function CancelDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const client = useQueryClient();
  const tillSessionId = useTillSession(job.branchId);
  const [reason, setReason] = React.useState("");
  const cancel = useMutation({
    mutationFn: () => api<Job>(`/repairs/${job.id}/cancel`, { method: "POST", body: { reason, tillSessionId } }),
    onSuccess: (v) => {
      client.setQueryData(["repair", String(job.id)], v);
      client.invalidateQueries({ queryKey: ["repairs"] });
      onClose();
    },
  });
  const refund = cmpMoney(job.advance, ZERO) > 0 && job.advanceMethod === "CASH";
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Cancel ${job.number}`}
      description={refund ? `The advance of ${money(job.advance)} is given back from your till.` : undefined}
      footer={
        <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={!reason.trim() || (refund && !tillSessionId)}>
          Cancel job
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
