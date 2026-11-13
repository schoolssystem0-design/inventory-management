"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CalendarClock, Download, Printer, Repeat, RotateCcw, Send, Truck } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { saleState, Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { Badge, DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { ArrangeDelivery } from "@/features/documents/arrange-delivery";
import { DocLines, lineValue, useDocLines } from "@/features/documents/doc-lines";
import { printHtml, receiptHtml, type ReceiptSize } from "@/features/receipts/receipt";
import { api, apiBlob, ApiError, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { date, dateTime, label, money, qty } from "@/lib/format";
import { addMoney, cmpMoney, isQty, maxRefund, percentOf, returnValue, subMoney, fromMilli, toMilli, ZERO } from "@/lib/money";
import { P, ShopLink, useCan, useFeature, useShopHref, useShopRouter } from "@/lib/shop-context";

type Sale = Schemas["SaleView"];

export default function SalePage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const feature = useFeature();
  const toHref = useShopHref();
  const sale = useQuery({ queryKey: ["sale", id], queryFn: () => api<Sale>(`/sales/${id}`) });
  const receipt = useQuery({ queryKey: ["receipt", id], queryFn: () => api<Schemas["ReceiptView"]>(`/sales/${id}/receipt`) });
  const [size, setSize] = React.useState<ReceiptSize | "">("");
  const [dialog, setDialog] = React.useState<"return" | "exchange" | "cancel" | "send" | "plan" | "delivery" | null>(null);

  if (sale.isPending) return <Skeleton className="h-96" />;
  if (sale.isError) return <ErrorState message={errorMessage(sale.error)} onRetry={() => sale.refetch()} />;
  const s = sale.data;
  const state = saleState(s);
  const open = state !== "CANCELLED" && state !== "RETURNED";
  const html = receipt.data ? receiptHtml(receipt.data, size || undefined) : null;

  return (
    <>
      <PageHeader
        back={{ href: toHref("/sales"), label: "Sales" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{s.number}</span> <Status value={state} />
          </span>
        }
        description={`${dateTime(s.at)} · ${s.branchName} · ${s.cashierName}`}
        actions={
          <>
            {open && can(P.SALES_RETURN) && (
              <>
                <Button variant="secondary" onClick={() => setDialog("return")}>
                  <RotateCcw /> Return
                </Button>
                <Button variant="secondary" onClick={() => setDialog("exchange")}>
                  <Repeat /> Exchange
                </Button>
              </>
            )}
            {open && feature("installments") && can(P.INSTALLMENTS_MANAGE) && s.customerId && s.due !== "0.00" && (
              <Button variant="secondary" onClick={() => setDialog("plan")}>
                <CalendarClock /> Installment plan
              </Button>
            )}
            {open && feature("delivery") && (can(P.DELIVERY_MANAGE) || can(P.POS_SELL)) && (
              <Button variant="secondary" onClick={() => setDialog("delivery")}>
                <Truck /> Arrange delivery
              </Button>
            )}
            {state === "COMPLETED" && can(P.SALES_CANCEL) && (
              <Button variant="danger" onClick={() => setDialog("cancel")}>
                <Ban /> Cancel bill
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="min-w-0 space-y-4">
          <Panel padded={false} title="Items">
            <Table>
              <thead>
                <tr>
                  <Th>Item</Th>
                  <Th align="right">Qty</Th>
                  <Th align="right">Price</Th>
                  <Th align="right">Discount</Th>
                  <Th align="right">Total</Th>
                  <Th align="right">Returned</Th>
                </tr>
              </thead>
              <tbody>
                {s.lines.map((l) => (
                  <tr key={l.id}>
                    <Td>
                      <p className="font-medium">{l.productName}</p>
                      {l.offerName && <p className="text-xs text-good">{l.offerName} · −{money(l.promoDiscount, false)}</p>}
                      <p className="text-xs text-faint">
                        {[l.serial && `IMEI ${l.serial}`, l.batchNo && `Batch ${l.batchNo} · exp ${date(l.expiry)}`, l.warrantyUntil && `Warranty till ${date(l.warrantyUntil)}`]
                          .filter(Boolean)
                          .join(" · ")}
                      </p>
                    </Td>
                    <Td align="right">
                      {qty(l.qty)} {l.unitName}
                    </Td>
                    <Td align="right">
                      {money(l.price, false)}
                      {l.price !== l.listPrice && <span className="block text-xs text-warn">list {money(l.listPrice, false)}</span>}
                    </Td>
                    <Td align="right">{l.discount !== "0.00" || l.billShare !== "0.00" ? money(addMoney(l.discount, l.billShare), false) : "—"}</Td>
                    <Td align="right">{money(l.total, false)}</Td>
                    <Td align="right">{l.qtyReturned !== "0" && l.qtyReturned !== "0.000" ? qty(l.qtyReturned) : "—"}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <dl className="grid grid-cols-2 gap-2 border-t border-line p-4 text-sm sm:ml-auto sm:max-w-sm">
              <dt className="text-muted">Subtotal</dt>
              <dd className="text-right font-mono">{money(s.subtotal)}</dd>
              {(s.lineDiscount !== "0.00" || s.billDiscount !== "0.00") && (
                <>
                  <dt className="text-muted">Discount{s.promoDiscount && s.promoDiscount !== "0.00" ? " (incl. offers)" : ""}</dt>
                  <dd className="text-right font-mono text-good">−{money(addMoney(s.lineDiscount, s.billDiscount))}</dd>
                </>
              )}
              {s.promoDiscount && s.promoDiscount !== "0.00" && (
                <>
                  <dt className="text-muted">Offers{s.billOfferName ? ` · ${s.billOfferName}` : ""}{s.couponCode ? ` · coupon ${s.couponCode}` : ""}</dt>
                  <dd className="text-right font-mono text-good">−{money(s.promoDiscount)}</dd>
                </>
              )}
              {s.tax !== "0.00" && (
                <>
                  <dt className="text-muted">Tax</dt>
                  <dd className="text-right font-mono">{money(s.tax)}</dd>
                </>
              )}
              <dt className="font-semibold">Total</dt>
              <dd className="text-right font-mono text-lg font-semibold">{money(s.total)}</dd>
              <dt className="text-muted">Paid</dt>
              <dd className="text-right font-mono">{money(s.paid)}</dd>
              {s.returned !== "0.00" && (
                <>
                  <dt className="text-muted">Returned</dt>
                  <dd className="text-right font-mono">{money(s.returned)}</dd>
                </>
              )}
              {s.due !== "0.00" && (
                <>
                  <dt className="font-semibold text-warn">Due</dt>
                  <dd className="text-right font-mono font-semibold text-warn">{money(s.due)}</dd>
                </>
              )}
              {can(P.COST_VIEW) && (
                <>
                  <dt className="text-muted">Profit</dt>
                  <dd className="text-right font-mono">{money(s.profit)}</dd>
                </>
              )}
            </dl>
          </Panel>

          <Panel title="Payments">
            <ul className="space-y-1 text-sm">
              {s.payments.map((p) => (
                <li key={p.id} className="flex justify-between gap-3">
                  <span>
                    {label(p.method)} {p.kind !== "SALE" && <Badge>{label(p.kind)}</Badge>} <span className="text-xs text-faint">{dateTime(p.at)}{p.reference ? ` · ${p.reference}` : ""}</span>
                  </span>
                  <span className="font-mono">{money(p.amount)}</span>
                </li>
              ))}
              {s.change !== "0.00" && <li className="flex justify-between text-muted"><span>Change given</span><span className="font-mono">{money(s.change)}</span></li>}
            </ul>
          </Panel>

          {s.returns.length > 0 && (
            <Panel title="Returns">
              <ul className="space-y-1 text-sm">
                {s.returns.map((r) => (
                  <li key={r.id} className="flex justify-between gap-3">
                    <span>
                      <span className="font-mono">{r.number}</span> <span className="text-xs text-faint">{dateTime(r.at)} · refund {label(r.refundMethod)}</span>
                    </span>
                    <span className="font-mono">
                      {money(r.value)} {r.refund !== r.value && <span className="text-xs text-faint">(refunded {money(r.refund)})</span>}
                    </span>
                  </li>
                ))}
              </ul>
            </Panel>
          )}

          <Panel title="Details">
            <DetailList
              items={[
                { label: "Customer", value: s.customerName ? `${s.customerName}${s.customerPhone ? ` · ${s.customerPhone}` : ""}` : "Walk-in" },
                { label: "Note", value: s.note },
                { label: "Loyalty points", value: s.pointsEarned || s.pointsUsed ? [s.pointsUsed ? `${s.pointsUsed} used` : null, s.pointsEarned ? `${s.pointsEarned} earned` : null].filter(Boolean).join(" · ") : null },
                { label: "Prescription", value: s.prescriptionId ? <ShopLink href="/prescriptions" className="text-aqua">Taken with this sale</ShopLink> : null },
                { label: "Approved", value: s.approvalNote },
                { label: "Cancelled", value: s.cancelledAt ? `${dateTime(s.cancelledAt)} — ${s.cancelReason ?? ""}` : null },
              ]}
            />
          </Panel>
        </div>

        <aside className="space-y-3">
          <Panel
            title="Receipt"
            actions={
              <Select value={size} onChange={(e) => setSize(e.target.value as ReceiptSize | "")} className="h-8 w-auto py-0 text-xs" aria-label="Receipt size">
                <option value="">Shop setting</option>
                <option value="MM80">80 mm</option>
                <option value="MM58">58 mm</option>
                <option value="A4">A4</option>
              </Select>
            }
          >
            {html ? <iframe title="Receipt preview" srcDoc={html} className="h-[520px] w-full rounded-lg bg-white" /> : <Skeleton className="h-[520px]" />}
            <div className="mt-3 flex flex-wrap gap-2">
              <Button onClick={() => html && receipt.data && void printHtml(receiptHtml(receipt.data, size || undefined, "DUPLICATE"))} disabled={!html}>
                <Printer /> Reprint
              </Button>
              <Button variant="secondary" onClick={() => setDialog("send")}>
                <Send /> Send
              </Button>
              <Button
                variant="ghost"
                onClick={async () => {
                  try {
                    saveBlob(await apiBlob(`/sales/${s.id}/pdf`), `${s.number}.pdf`);
                  }
                  catch (e) {
                    toast.error(errorMessage(e));
                  }
                }}
              >
                <Download /> PDF
              </Button>
            </div>
          </Panel>
        </aside>
      </div>

      {dialog === "return" && <ReturnDialog sale={s} onClose={() => setDialog(null)} />}
      {dialog === "exchange" && <ReturnDialog sale={s} exchange onClose={() => setDialog(null)} />}
      {dialog === "cancel" && <CancelDialog sale={s} onClose={() => setDialog(null)} />}
      {dialog === "send" && <SendDialog sale={s} onClose={() => setDialog(null)} />}
      {dialog === "plan" && <PlanDialog sale={s} onClose={() => setDialog(null)} />}
      {dialog === "delivery" && <ArrangeDelivery saleId={s.id} customerName={s.customerName} onClose={() => setDialog(null)} />}
    </>
  );
}

function useTillSession(branchId: number) {
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  return sessions.data?.find((x) => x.branchId === branchId)?.id ?? null;
}

function ReturnDialog({ sale, exchange, onClose }: { sale: Sale; exchange?: boolean; onClose: () => void }) {
  const client = useQueryClient();
  const approval = useApproval();
  const tillSessionId = useTillSession(sale.branchId);
  const methods = useQuery({ queryKey: ["payment-methods"], queryFn: () => api<Schemas["PaymentMethodView"][]>("/payment-methods") });
  const [qtys, setQtys] = React.useState<Record<number, string>>({});
  const [restock, setRestock] = React.useState<Record<number, boolean>>({});
  const [reason, setReason] = React.useState("");
  const [method, setMethod] = React.useState("CASH");
  const doc = useDocLines();
  const key = React.useRef(crypto.randomUUID());
  const [paid, setPaid] = React.useState("");

  const open = sale.lines.filter((l) => cmpQty(l.qty, l.qtyReturned) > 0);
  const chosen = open.filter((l) => qtys[l.id] && isQty(qtys[l.id]) && toMilli(qtys[l.id]) > BigInt(0));
  const value = chosen.reduce((sum, l) => addMoney(sum, returnValue(l.total, l.qty, l.qtyReturned, qtys[l.id])), ZERO);
  const refundable = maxRefund(sale.total, sale.returned, sale.paid, value);
  const newTotal = doc.lines.reduce((sum, l) => addMoney(sum, lineValue(l, "price")), ZERO);
  const difference = subMoney(newTotal, value);
  const tooMuch = chosen.some((l) => cmpQty(qtys[l.id], subQty(l.qty, l.qtyReturned)) > 0);

  const submit = useMutation({
    mutationFn: async () => {
      const returnLines = chosen.map((l) => ({ saleLineId: l.id, qty: qtys[l.id], restock: restock[l.id] ?? true }));
      if (exchange) {
        const body = {
          saleId: sale.id,
          reason,
          returnLines,
          newSale: {
            tillSessionId,
            customerId: sale.customerId,
            lines: doc.lines.map((l) => ({
              productId: l.product.id,
              unitId: l.unitId === l.product.unitId ? null : l.unitId,
              qty: l.product.kind === "SERIAL" ? null : l.qty,
              price: l.price,
              discount: l.discount || null,
              serials: l.product.kind === "SERIAL" ? l.serials.split(/[\s,;]+/).filter(Boolean) : null,
              batchId: l.batchId,
            })),
            payments: cmpMoney(paid || "0", ZERO) > 0 ? [{ method, amount: paid }] : [],
          },
        };
        return approval.run((headers) => api<Schemas["ExchangeView"]>("/exchanges", { method: "POST", body, headers: { "Idempotency-Key": key.current, ...headers } }));
      }
      const body = { tillSessionId, reason, lines: returnLines, refundMethod: method };
      return approval.run((headers) => api<Schemas["ReturnView"]>(`/sales/${sale.id}/returns`, { method: "POST", body, headers: { "Idempotency-Key": key.current, ...headers } }));
    },
    onSuccess: () => {
      toast.success(exchange ? "Exchange done" : "Return done");
      client.invalidateQueries({ queryKey: ["sale", String(sale.id)] });
      client.invalidateQueries({ queryKey: ["receipt", String(sale.id)] });
      client.invalidateQueries({ queryKey: ["sales"] });
      onClose();
    },
    onError: (e) => {
      if (!(e instanceof ApiError && e.code === "approval_required")) toast.error(errorMessage(e));
    },
  });
  const refundMethods = [
    ...(methods.data ?? []).filter((m) => m.active && m.key !== "ACCOUNT" && m.key !== "CREDIT_NOTE"),
    ...(sale.customerId ? [{ key: "ACCOUNT", label: "Customer's account" }] : []),
    { key: "CREDIT_NOTE", label: "Credit note" },
  ];

  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title={exchange ? `Exchange items of ${sale.number}` : `Return items of ${sale.number}`}
      description={exchange ? "Choose what comes back, then the new items. The difference is paid or refunded." : "Choose what comes back. A return first clears what the customer still owes."}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
          <Button onClick={() => submit.mutate()} loading={submit.isPending} disabled={!chosen.length || tooMuch || !reason.trim() || (exchange && !doc.lines.length)}>
            {exchange ? "Exchange" : `Return ${money(value)}`}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Table className="min-w-[520px]">
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Can return</Th>
              <Th align="right">Return</Th>
              <Th>Back in stock</Th>
            </tr>
          </thead>
          <tbody>
            {open.map((l) => (
              <tr key={l.id}>
                <Td>
                  {l.productName}
                  {l.serial && <span className="block font-mono text-xs text-faint">{l.serial}</span>}
                </Td>
                <Td align="right">{qty(subQty(l.qty, l.qtyReturned))}</Td>
                <Td align="right">
                  <Input
                    value={qtys[l.id] ?? ""}
                    onChange={(e) => setQtys((q) => ({ ...q, [l.id]: e.target.value }))}
                    inputMode="decimal"
                    className="ml-auto w-20 text-right font-mono"
                    aria-label={`Return quantity of ${l.productName}`}
                    placeholder="0"
                  />
                </Td>
                <Td>
                  <label className="flex items-center gap-2 text-sm text-muted">
                    <input type="checkbox" className="accent-[#4fbfae]" checked={restock[l.id] ?? true} onChange={(e) => setRestock((r) => ({ ...r, [l.id]: e.target.checked }))} />
                    {restock[l.id] ?? true ? "Sellable" : "Damaged"}
                  </label>
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
        {tooMuch && <p className="text-sm text-bad">More than was sold (less earlier returns).</p>}

        {exchange && (
          <div className="space-y-2">
            <p className="text-sm font-semibold">New items</p>
            <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} config={{ price: true, unit: true, discount: true, serials: "existing", batch: "pick" }} branchId={sale.branchId} />
          </div>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Reason" required>
            <Textarea value={reason} onChange={(e) => setReason(e.target.value)} className="min-h-12" placeholder="Wrong size, faulty, changed mind…" />
          </Field>
          <Field label={exchange ? "Customer pays / is refunded by" : "Refund by"}>
            <Select value={method} onChange={(e) => setMethod(e.target.value)}>
              {refundMethods.map((m) => (
                <option key={m.key} value={m.key}>
                  {m.label}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <dl className="grid grid-cols-2 gap-2 rounded-xl bg-raised/60 p-4 text-sm">
          <dt className="text-muted">Value of items back</dt>
          <dd className="text-right font-mono">{money(value)}</dd>
          {!exchange && (
            <>
              <dt className="text-muted">Clears what is owed</dt>
              <dd className="text-right font-mono">{money(subMoney(value, refundable))}</dd>
              <dt className="font-semibold">Refund</dt>
              <dd className="text-right font-mono font-semibold">{money(refundable)}</dd>
            </>
          )}
          {exchange && (
            <>
              <dt className="text-muted">New items (before tax)</dt>
              <dd className="text-right font-mono">{money(newTotal)}</dd>
              <dt className="font-semibold">{cmpMoney(difference, ZERO) >= 0 ? "Customer pays" : "Refund"}</dt>
              <dd className="text-right font-mono font-semibold">{money(difference.replace("-", ""))}</dd>
            </>
          )}
        </dl>
        {exchange && cmpMoney(difference, ZERO) > 0 && (
          <Field label="Amount received now" hint="The server books the exact difference including tax; anything short goes on the customer's account.">
            <Input value={paid} onChange={(e) => setPaid(e.target.value)} inputMode="decimal" className="text-right font-mono" placeholder={difference} />
          </Field>
        )}
      </div>
      {approval.dialog}
    </Dialog>
  );
}

function CancelDialog({ sale, onClose }: { sale: Sale; onClose: () => void }) {
  const client = useQueryClient();
  const approval = useApproval();
  const tillSessionId = useTillSession(sale.branchId);
  const [reason, setReason] = React.useState("");
  const cancel = useMutation({
    mutationFn: () => approval.run((headers) => api(`/sales/${sale.id}/cancel`, { method: "POST", body: { reason, tillSessionId }, headers })),
    onSuccess: () => {
      toast.success(`${sale.number} cancelled`);
      client.invalidateQueries({ queryKey: ["sale", String(sale.id)] });
      client.invalidateQueries({ queryKey: ["receipt", String(sale.id)] });
      client.invalidateQueries({ queryKey: ["sales"] });
      onClose();
    },
    onError: (e) => {
      if (!(e instanceof ApiError && e.code === "approval_required")) toast.error(errorMessage(e));
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Cancel ${sale.number}?`}
      description="Stock goes back, money taken is paid out of the till and any amount on account is removed. The bill stays in the records as cancelled."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Keep the bill
          </Button>
          <Button variant="danger" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={!reason.trim()}>
            Cancel bill
          </Button>
        </>
      }
    >
      <Field label="Reason" required>
        <Textarea value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </Field>
      {approval.dialog}
    </Dialog>
  );
}

function SendDialog({ sale, onClose }: { sale: Sale; onClose: () => void }) {
  const [phone, setPhone] = React.useState(sale.customerPhone ?? "");
  const [email, setEmail] = React.useState("");
  const send = useMutation({
    mutationFn: () => api<Schemas["Sent"]>(`/sales/${sale.id}/send`, { method: "POST", body: { phone: phone || null, email: email || null } }),
    onSuccess: () => {
      toast.success("Receipt sent");
      onClose();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Send the receipt"
      description="By SMS (a link to the receipt) or by email (the PDF)."
      footer={
        <Button onClick={() => send.mutate()} loading={send.isPending} disabled={!phone.trim() && !email.trim()}>
          <Send /> Send
        </Button>
      }
    >
      <div className="space-y-3">
        <Field label="Phone">
          <Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" placeholder="03xx xxxxxxx" />
        </Field>
        <Field label="Email">
          <Input value={email} onChange={(e) => setEmail(e.target.value)} type="email" />
        </Field>
      </div>
    </Dialog>
  );
}

function cmpQty(a: string, b: string) {
  const d = toMilli(a) - toMilli(b);
  return d === BigInt(0) ? 0 : d > BigInt(0) ? 1 : -1;
}

function subQty(a: string, b: string) {
  return fromMilli(toMilli(a) - toMilli(b));
}

/** Installments (MOB-08): the amount still owed on this invoice, split into monthly installments. */
function PlanDialog({ sale, onClose }: { sale: Sale; onClose: () => void }) {
  const router = useShopRouter();
  const [f, setF] = React.useState({ months: "6", markupPercent: "0", firstDue: "", graceDays: "3", lateFeePerDay: "0", guarantorName: "", guarantorCnic: "", guarantorPhone: "", guarantorAddress: "", notes: "" });
  const set = (k: keyof typeof f, v: string) => setF((x) => ({ ...x, [k]: v }));
  const months = Math.max(1, Number(f.months) || 1);
  const markupOk = /^\d+(\.\d{1,4})?$/.test(f.markupPercent) && Number(f.markupPercent) <= 100;
  const totalText = money(addMoney(sale.due, markupOk ? percentOf(sale.due, f.markupPercent) : ZERO));
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["InstallmentPlanView"]>("/installment-plans", {
        method: "POST",
        body: {
          saleId: sale.id,
          months,
          markupPercent: f.markupPercent || "0",
          firstDue: f.firstDue || null,
          graceDays: Number(f.graceDays) || 0,
          lateFeePerDay: f.lateFeePerDay || "0",
          guarantorName: f.guarantorName,
          guarantorCnic: f.guarantorCnic,
          guarantorPhone: f.guarantorPhone,
          guarantorAddress: f.guarantorAddress || null,
          notes: f.notes || null,
        },
      }),
    onSuccess: (plan) => {
      toast.success(`Plan ${plan.number} made`);
      router.push(`/installments/${plan.id}`);
    },
  });
  const err = (n: string) => (save.error instanceof ApiError ? save.error.field(n) : undefined);
  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title="Installment plan"
      description={`${money(sale.due)} owed by ${sale.customerName}. About ${totalText} over ${months} months.`}
      footer={
        <Button type="submit" form="plan-form" loading={save.isPending}>
          Make the plan
        </Button>
      }
    >
      <form
        id="plan-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Months" error={err("months")}>
            <Input value={f.months} onChange={(e) => set("months", e.target.value)} inputMode="numeric" autoFocus />
          </Field>
          <Field label="Markup %" error={err("markupPercent")}>
            <Input value={f.markupPercent} onChange={(e) => set("markupPercent", e.target.value)} inputMode="decimal" />
          </Field>
          <Field label="First installment on" hint="Empty = a month from today" error={err("firstDue")}>
            <Input type="date" value={f.firstDue} onChange={(e) => set("firstDue", e.target.value)} />
          </Field>
          <Field label="Grace days" error={err("graceDays")}>
            <Input value={f.graceDays} onChange={(e) => set("graceDays", e.target.value)} inputMode="numeric" />
          </Field>
          <Field label="Late fee a day (Rs)" error={err("lateFeePerDay")}>
            <Input value={f.lateFeePerDay} onChange={(e) => set("lateFeePerDay", e.target.value)} inputMode="decimal" />
          </Field>
        </div>
        <fieldset className="grid gap-3 rounded-xl border border-line p-3 sm:grid-cols-2">
          <legend className="px-1 text-sm font-medium">Guarantor</legend>
          <Field label="Name" required error={err("guarantorName")}>
            <Input value={f.guarantorName} onChange={(e) => set("guarantorName", e.target.value)} />
          </Field>
          <Field label="CNIC" required error={err("guarantorCnic")}>
            <Input value={f.guarantorCnic} onChange={(e) => set("guarantorCnic", e.target.value)} placeholder="35202-1234567-1" inputMode="numeric" />
          </Field>
          <Field label="Phone" required error={err("guarantorPhone")}>
            <Input value={f.guarantorPhone} onChange={(e) => set("guarantorPhone", e.target.value)} inputMode="tel" />
          </Field>
          <Field label="Address">
            <Input value={f.guarantorAddress} onChange={(e) => set("guarantorAddress", e.target.value)} />
          </Field>
        </fieldset>
        <Field label="Notes">
          <Textarea value={f.notes} onChange={(e) => set("notes", e.target.value)} rows={2} />
        </Field>
        {save.error && !(save.error instanceof ApiError && save.error.fieldErrors?.length) && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}
