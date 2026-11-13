"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { HandCoins, Printer, XCircle } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, StatCard, Table, Td, Th } from "@/components/ui/layout";
import { docHtml } from "@/features/documents/print-doc";
import { useShopHeader, useTillSession } from "@/features/documents/till";
import { printHtml } from "@/features/receipts/receipt";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, label, money } from "@/lib/format";
import { addMoney, cmpMoney, isMoney, ZERO } from "@/lib/money";
import { P, ShopLink, useCan, useShopHref } from "@/lib/shop-context";

type Plan = Schemas["InstallmentPlanView"];

export default function InstallmentPlanPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const header = useShopHeader();
  const [dialog, setDialog] = React.useState<"receive" | "cancel" | null>(null);
  const plan = useQuery({ queryKey: ["installment-plan", id], queryFn: () => api<Plan>(`/installment-plans/${id}`) });
  if (plan.isPending) return <Skeleton className="h-96" />;
  if (plan.isError) return <ErrorState message={errorMessage(plan.error)} onRetry={() => plan.refetch()} />;
  const p = plan.data;
  const active = p.status === "ACTIVE";
  const print = () =>
    void printHtml(
      docHtml({
        shopName: header.name,
        shopLines: header.lines,
        title: "Installment plan",
        number: p.number,
        date: date(p.date),
        sections: [
          {
            kind: "fields",
            rows: [
              ["Customer", p.customerName],
              ["Phone", p.customerPhone],
              ["Invoice", p.saleNumber],
              ["Amount owed", money(p.principal)],
              ["Markup", cmpMoney(p.markup, ZERO) > 0 ? `${p.markupPercent}% · ${money(p.markup)}` : null],
              ["Total to pay", money(p.total)],
              ["Late fee", cmpMoney(p.lateFeePerDay, ZERO) > 0 ? `${money(p.lateFeePerDay)} a day after ${p.graceDays} days` : null],
            ],
          },
          {
            kind: "fields",
            title: "Guarantor",
            rows: [
              ["Name", p.guarantorName],
              ["CNIC", p.guarantorCnic],
              ["Phone", p.guarantorPhone],
              ["Address", p.guarantorAddress],
            ],
          },
          {
            kind: "table",
            title: "Schedule",
            head: ["No.", "Due on", "Amount", "Paid", "Paid on"],
            right: [2, 3],
            rows: p.dues.map((d) => [d.no, date(d.dueOn), money(d.amount, false), cmpMoney(d.paid, ZERO) > 0 ? money(d.paid, false) : "", d.paidOn ? date(d.paidOn) : ""]),
            foot: ["", "Total", money(p.total, false), money(p.paid, false), ""],
          },
          { kind: "signatures", labels: ["Customer", "Guarantor", "For the shop"] },
        ],
      }),
    );
  return (
    <>
      <PageHeader
        back={{ href: toHref("/installments"), label: "Installments" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{p.number}</span> <Status value={p.status} />
          </span>
        }
        description={
          <>
            <ShopLink href={`/customers/${p.customerId}`} className="hover:text-aqua">
              {p.customerName}
            </ShopLink>{" "}
            · invoice{" "}
            <ShopLink href={`/sales/${p.saleId}`} className="font-mono hover:text-aqua">
              {p.saleNumber}
            </ShopLink>
          </>
        }
        actions={
          <>
            <Button variant="secondary" onClick={print}>
              <Printer /> Print
            </Button>
            {active && can(P.CUSTOMERS_PAYMENTS) && (
              <Button onClick={() => setDialog("receive")}>
                <HandCoins /> Take installment
              </Button>
            )}
            {active && can(P.INSTALLMENTS_MANAGE) && p.receipts.length === 0 && (
              <Button variant="ghost" onClick={() => setDialog("cancel")}>
                <XCircle /> Cancel plan
              </Button>
            )}
          </>
        }
      />
      <div className="mb-4 grid gap-3 sm:grid-cols-4">
        <StatCard label="Total to pay" value={money(p.total)} hint={cmpMoney(p.markup, ZERO) > 0 ? `${money(p.principal)} + ${p.markupPercent}% markup` : undefined} />
        <StatCard label="Paid" value={money(p.paid)} tone="good" />
        <StatCard label="Left" value={money(p.left)} hint={cmpMoney(p.lateFees, ZERO) > 0 ? `includes ${money(p.lateFees)} late fees` : undefined} />
        <StatCard label="Late" value={p.overdueCount ? money(p.overdueAmount) : "Nothing"} tone={p.overdueCount ? "bad" : undefined} hint={p.nextDueOn ? `Next ${date(p.nextDueOn)}` : undefined} />
      </div>
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Panel title="Schedule" padded={false}>
          <Table>
            <thead>
              <tr>
                <Th>No.</Th>
                <Th>Due on</Th>
                <Th align="right">Amount</Th>
                <Th align="right">Late fee</Th>
                <Th align="right">Paid</Th>
                <Th>State</Th>
              </tr>
            </thead>
            <tbody>
              {p.dues.map((d) => (
                <tr key={d.id}>
                  <Td>{d.no}</Td>
                  <Td>{date(d.dueOn)}</Td>
                  <Td align="right">{money(d.amount, false)}</Td>
                  <Td align="right">{cmpMoney(d.lateFee, ZERO) > 0 ? money(d.lateFee, false) : "—"}</Td>
                  <Td align="right">{cmpMoney(d.paid, ZERO) > 0 ? money(d.paid, false) : "—"}</Td>
                  <Td>
                    <Status value={d.state} />
                    {d.state === "LATE" && <span className="ml-2 text-xs text-bad">{d.daysLate} days</span>}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Panel>
        <div className="space-y-4">
          <Panel title="Guarantor">
            <DetailList
              items={[
                { label: "Name", value: p.guarantorName },
                { label: "CNIC", value: p.guarantorCnic },
                { label: "Phone", value: p.guarantorPhone },
                { label: "Address", value: p.guarantorAddress },
              ]}
            />
          </Panel>
          <Panel title="Terms">
            <DetailList
              items={[
                { label: "Months", value: String(p.months) },
                { label: "Markup", value: `${p.markupPercent}%` },
                { label: "Grace days", value: String(p.graceDays) },
                { label: "Late fee a day", value: money(p.lateFeePerDay) },
                { label: "Made", value: `${date(p.date)} by ${p.actorName}` },
                { label: "Notes", value: p.notes },
              ]}
            />
          </Panel>
          <Panel title="Payments" padded={false}>
            {p.receipts.length === 0 ? (
              <p className="px-5 py-4 text-sm text-muted">No installment taken yet.</p>
            ) : (
              <ul className="divide-y divide-line text-sm">
                {p.receipts.map((r) => (
                  <li key={r.id} className="flex justify-between px-5 py-2.5">
                    <span>
                      {money(r.amount)} <span className="text-muted">· {label(r.method)}</span>
                      <span className="block text-xs text-faint">
                        {r.paymentNumber} · {dateTime(r.at)}
                      </span>
                    </span>
                    {cmpMoney(r.lateFee, ZERO) > 0 && <span className="text-xs text-warn">late fee {money(r.lateFee)}</span>}
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>
      {dialog === "receive" && <ReceiveDialog plan={p} onClose={() => setDialog(null)} />}
      {dialog === "cancel" && <CancelPlan plan={p} onClose={() => setDialog(null)} />}
    </>
  );
}

function ReceiveDialog({ plan, onClose }: { plan: Plan; onClose: () => void }) {
  const client = useQueryClient();
  const tillSessionId = useTillSession();
  const next = plan.dues.find((d) => cmpMoney(d.left, ZERO) > 0);
  // Late fees due now are added when the payment is taken.
  const lateNow = plan.dues.filter((d) => d.state === "LATE").reduce((s, d) => addMoney(s, d.left), ZERO);
  const [amount, setAmount] = React.useState(cmpMoney(lateNow, ZERO) > 0 ? lateNow : (next?.left ?? ""));
  const [method, setMethod] = React.useState("CASH");
  const [reference, setReference] = React.useState("");
  const receive = useMutation({
    mutationFn: () =>
      api<Plan>(`/installment-plans/${plan.id}/receive`, {
        method: "POST",
        body: { amount, method, reference: reference || null, tillSessionId },
        headers: { "Idempotency-Key": crypto.randomUUID() },
      }),
    onSuccess: (v) => {
      client.setQueryData(["installment-plan", String(plan.id)], v);
      client.invalidateQueries({ queryKey: ["installment-plans"] });
      toast.success(v.status === "COMPLETED" ? "Last installment taken — plan completed" : "Installment taken");
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Take an installment"
      description={`${plan.customerName} · ${money(plan.left)} left on the plan`}
      footer={
        <Button onClick={() => receive.mutate()} loading={receive.isPending} disabled={!isMoney(amount) || (method === "CASH" && !tillSessionId)}>
          Take {isMoney(amount) ? money(amount) : ""}
        </Button>
      }
    >
      <div className="space-y-4">
        {plan.overdueCount > 0 && <p className="rounded-xl bg-bad/10 p-3 text-sm text-bad">{plan.overdueCount} installments are late. Late fees are added to the account when this payment is taken.</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Amount">
            <Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" className="font-mono" autoFocus />
          </Field>
          <Field label="Paid by">
            <Select value={method} onChange={(e) => setMethod(e.target.value)}>
              <option value="CASH">Cash</option>
              <option value="BANK">Bank</option>
              <option value="JAZZCASH">JazzCash</option>
              <option value="EASYPAISA">Easypaisa</option>
              <option value="CARD">Card</option>
              <option value="CHEQUE">Cheque</option>
            </Select>
          </Field>
        </div>
        {method !== "CASH" && (
          <Field label="Reference">
            <Input value={reference} onChange={(e) => setReference(e.target.value)} />
          </Field>
        )}
        {method === "CASH" && !tillSessionId && <p className="text-sm text-warn">Open your till to take cash.</p>}
        {receive.error && <p className="text-sm text-bad">{errorMessage(receive.error)}</p>}
      </div>
    </Dialog>
  );
}

function CancelPlan({ plan, onClose }: { plan: Plan; onClose: () => void }) {
  const client = useQueryClient();
  const [reason, setReason] = React.useState("");
  const cancel = useMutation({
    mutationFn: () => api<Plan>(`/installment-plans/${plan.id}/cancel`, { method: "POST", body: { reason } }),
    onSuccess: (v) => {
      client.setQueryData(["installment-plan", String(plan.id)], v);
      client.invalidateQueries({ queryKey: ["installment-plans"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Cancel ${plan.number}`}
      description={cmpMoney(plan.markup, ZERO) > 0 ? `The markup of ${money(plan.markup)} comes off the customer's account. The invoice stays on the account.` : "The invoice stays on the customer's account."}
      footer={
        <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={!reason.trim()}>
          Cancel plan
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
