"use client";

import { useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import { Plus, Trash2, UserPlus } from "lucide-react";
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Select } from "@/components/ui/fields";
import { Badge, Empty } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { date, dateTime, money, qty as fmtQty } from "@/lib/format";
import { addMoney, cmpMoney, fromPaisa, isMoney, toPaisa, ZERO } from "@/lib/money";
import { type Cart, type CartLine, type Payment, parseDiscount, tender } from "./cart";

/* ------------------------------------------------------------------ payment */

/** Round-up notes for quick cash: exact, then the next 100 / 500 / 1,000 / 5,000. */
export function quickCash(total: string): string[] {
  const t = Number(total);
  const out = new Set<string>([total]);
  for (const step of [100, 500, 1000, 5000]) {
    const up = Math.ceil(t / step) * step;
    if (up > t) out.add(up.toFixed(2));
  }
  return [...out].slice(0, 5);
}

export function PaymentDialog({
  open,
  onClose,
  total,
  methods,
  customer,
  loyalty,
  onPay,
  busy,
}: {
  open: boolean;
  onClose: () => void;
  total: string;
  methods: Schemas["PaymentMethodView"][];
  customer: Cart["customer"];
  /** The customer's loyalty points (module loyalty), to pay part of the bill with them. */
  loyalty?: Loyalty | null;
  onPay: (payments: Payment[]) => void;
  busy: boolean;
}) {
  if (!open) return null;
  return <PaymentBody onClose={onClose} total={total} methods={methods} customer={customer} loyalty={loyalty ?? null} onPay={onPay} busy={busy} />;
}

type Loyalty = { points: number; pointValue: string; minRedeem: number };

/** The most points (as rupees) that can pay this bill: whole points, no more than the customer has. */
function pointsAmount(l: Loyalty, total: string): { points: number; amount: string } {
  const value = toPaisa(l.pointValue);
  if (value <= BigInt(0)) return { points: 0, amount: ZERO };
  const byBill = toPaisa(total) / value;
  const n = BigInt(l.points) < byBill ? BigInt(l.points) : byBill;
  return { points: Number(n), amount: fromPaisa(n * value) };
}

function PaymentBody({
  onClose,
  total,
  methods,
  customer,
  loyalty,
  onPay,
  busy,
}: {
  onClose: () => void;
  total: string;
  methods: Schemas["PaymentMethodView"][];
  customer: Cart["customer"];
  loyalty: Loyalty | null;
  onPay: (payments: Payment[]) => void;
  busy: boolean;
}) {
  const usable = methods.filter((m) => m.active && m.key !== "ACCOUNT" && m.key !== "CREDIT_NOTE");
  const canPoints = !!loyalty && !!customer && pointsAmount(loyalty, total).points >= Math.max(1, loyalty.minRedeem);
  const choices = canPoints ? [...usable, { key: "POINTS", label: "Loyalty points" } as Schemas["PaymentMethodView"]] : usable;
  const onAccount = methods.some((m) => m.active && m.key === "ACCOUNT");
  const [payments, setPayments] = React.useState<Payment[]>([{ method: usable[0]?.key ?? "CASH", amount: total }]);
  const t = tender(total, payments);
  const short = cmpMoney(t.due, ZERO) > 0;
  const blocked = Boolean(t.error) || (short && (!customer || !onAccount)) || payments.some((p) => p.amount && !isMoney(p.amount));
  const first = React.useRef<HTMLInputElement>(null);
  React.useEffect(() => {
    first.current?.select();
  }, []);

  function set(i: number, patch: Partial<Payment>) {
    setPayments((ps) => ps.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  }

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Collect ${money(total)}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Back to bill
          </Button>
          <Button type="submit" form="pay-form" size="lg" loading={busy} disabled={blocked}>
            {short ? `Complete — ${money(t.due)} on account` : "Complete sale"}
          </Button>
        </>
      }
    >
      <form
        id="pay-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!blocked) onPay(payments);
        }}
      >
        {payments.map((p, i) => (
          <div key={i} className="flex items-end gap-2">
            <Field label={i === 0 ? "Method" : "Also paid by"} className="w-32 shrink-0">
              <Select value={p.method} onChange={(e) => set(i, { method: e.target.value })}>
                {choices.map((m) => (
                  <option key={m.key} value={m.key}>
                    {m.label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Amount" className="min-w-24 flex-1">
              <AmountInput ref={i === 0 ? first : undefined} value={p.amount} onChange={(e) => set(i, { amount: e.target.value.replace(/[^\d.]/g, "") })} className="text-lg" />
            </Field>
            {p.method !== "CASH" && p.method !== "POINTS" && (
              <Field label="Reference" className="w-28 shrink-0">
                <Input value={p.reference ?? ""} onChange={(e) => set(i, { reference: e.target.value })} placeholder="Txn / slip" />
              </Field>
            )}
            {payments.length > 1 && (
              <button type="button" className="mb-2 rounded-lg p-1.5 text-faint hover:text-bad" onClick={() => setPayments((ps) => ps.filter((_, j) => j !== i))} aria-label="Remove payment">
                <Trash2 className="size-4" />
              </button>
            )}
          </div>
        ))}
        {payments[0]?.method === "CASH" && payments.length === 1 && (
          <div className="flex flex-wrap gap-2">
            {quickCash(total).map((v) => (
              <button key={v} type="button" className="rounded-lg bg-raised px-3 py-1.5 font-mono text-sm hover:bg-aqua/15" onClick={() => set(0, { amount: v })}>
                {money(v, false)}
              </button>
            ))}
          </div>
        )}
        {usable.length > 1 && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              const rest = cmpMoney(t.due, ZERO) > 0 ? t.due : "";
              const next = usable.find((m) => !payments.some((p) => p.method === m.key))?.key ?? usable[0].key;
              setPayments((ps) => [...ps, { method: next, amount: rest }]);
            }}
          >
            <Plus /> Split payment
          </Button>
        )}
        {loyalty && customer && (
          <div className="flex items-center justify-between gap-3 rounded-xl border border-line px-3 py-2 text-sm">
            <span>
              {customer.name} has <span className="font-semibold">{loyalty.points.toLocaleString()} points</span>
              <span className="text-muted"> · Rs {loyalty.pointValue} each</span>
            </span>
            {canPoints && !payments.some((p) => p.method === "POINTS") && (
              <Button
                size="sm"
                variant="secondary"
                onClick={() => {
                  const use = pointsAmount(loyalty, total);
                  setPayments((ps) => {
                    const rest = subtractFrom(ps, use.amount);
                    return [{ method: "POINTS", amount: use.amount }, ...rest];
                  });
                }}
              >
                Use {pointsAmount(loyalty, total).points} points
              </Button>
            )}
            {!canPoints && loyalty.points > 0 && <span className="text-xs text-faint">at least {loyalty.minRedeem} points to use</span>}
          </div>
        )}
        <dl className="grid grid-cols-2 gap-3 rounded-xl bg-raised/60 p-4 text-sm">
          <dt className="text-muted">Paid</dt>
          <dd className="text-right font-mono">{money(t.paid)}</dd>
          {cmpMoney(t.change, ZERO) > 0 && (
            <>
              <dt className="text-lg font-semibold text-good">Change</dt>
              <dd className="text-right font-mono text-2xl font-bold text-good">{money(t.change)}</dd>
            </>
          )}
          {short && (
            <>
              <dt className="font-semibold text-warn">Still due</dt>
              <dd className="text-right font-mono text-lg font-semibold text-warn">{money(t.due)}</dd>
            </>
          )}
        </dl>
        {t.error && <p className="text-sm text-bad">{t.error}</p>}
        {short && !customer && <p className="text-sm text-warn">Choose a customer (F4) to put the rest on their account.</p>}
        {short && customer && !onAccount && <p className="text-sm text-warn">Selling on account is switched off in settings.</p>}
        {short && customer && onAccount && (
          <p className="text-sm text-muted">
            {money(t.due)} goes on {customer.name}&apos;s account
            {customer.balance ? ` (balance now ${money(customer.balance)})` : ""}.
          </p>
        )}
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ customer */

export function CustomerDialog({
  open,
  onClose,
  onPick,
  canCreate,
}: {
  open: boolean;
  onClose: () => void;
  onPick: (customer: Cart["customer"], quick: Cart["quick"]) => void;
  canCreate: boolean;
}) {
  const [q, setQ] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 200);
    return () => clearTimeout(t);
  }, [q]);
  const results = useQuery({
    queryKey: ["pos-customers", debounced],
    queryFn: () => api<Schemas["PageResponseCustomerListItem"]>(`/customers?q=${encodeURIComponent(debounced)}&active=true&size=8`),
    enabled: open && debounced.length >= 2,
  });
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title="Customer" description="Search by name, phone or code, or add a new one with their phone.">
      <div className="space-y-4">
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, phone or code" autoFocus aria-label="Find customer" />
        <ul className="-mx-2 max-h-64 space-y-1 overflow-y-auto">
          {results.data?.items.map((c) => (
            <li key={c.id}>
              <button
                className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left hover:bg-raised"
                onClick={() => onPick({ id: c.id, name: c.name, phone: c.phone, balance: c.balance, creditLimit: c.creditLimit }, null)}
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium">{c.name}</span>
                  <span className="block text-xs text-faint">
                    {c.code} · {c.phone ?? "no phone"}
                  </span>
                </span>
                {c.balance && c.balance !== "0.00" && <Badge tone={c.overLimit ? "bad" : "warn"}>Owes {money(c.balance)}</Badge>}
              </button>
            </li>
          ))}
          {results.data && results.data.items.length === 0 && <li className="px-3 py-2 text-sm text-faint">No customer found</li>}
        </ul>
        {canCreate && (
          <form
            className="space-y-3 border-t border-line pt-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (/^\+?\d[\d -]{6,}$/.test(phone.trim())) onPick(null, { name: name.trim(), phone: phone.trim() });
            }}
          >
            <p className="text-sm font-semibold">New customer</p>
            <div className="grid grid-cols-2 gap-2">
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" aria-label="New customer name" />
              <Input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="Phone 03xx…" inputMode="tel" aria-label="New customer phone" />
            </div>
            <Button type="submit" variant="secondary" size="sm" disabled={!/^\+?\d[\d -]{6,}$/.test(phone.trim())}>
              <UserPlus /> Use this customer
            </Button>
          </form>
        )}
      </div>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ held bills */

export function HeldDialog({
  open,
  onClose,
  branchId,
  onResume,
  onDelete,
}: {
  open: boolean;
  onClose: () => void;
  branchId: number | null;
  onResume: (held: Schemas["HeldSaleView"]) => void;
  onDelete: (id: number) => void;
}) {
  const held = useQuery({
    queryKey: ["held-sales", branchId],
    queryFn: () => api<Schemas["HeldSaleView"][]>(`/held-sales${branchId ? `?branchId=${branchId}` : ""}`),
    enabled: open,
  });
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title="Held bills" description="Bills parked at this branch. Resume one to finish it.">
      {held.data?.length === 0 && <Empty title="No held bills" />}
      <ul className="-mx-2 space-y-1">
        {held.data?.map((h) => {
          const cart = h.cart as unknown as Partial<Cart> | null;
          const lines = cart?.lines?.length ?? 0;
          return (
            <li key={h.id} className="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-raised">
              <button className="min-w-0 flex-1 text-left" onClick={() => onResume(h)}>
                <span className="block text-sm font-medium">{h.label || `Bill of ${lines} item${lines === 1 ? "" : "s"}`}</span>
                <span className="block text-xs text-faint">
                  {h.actorName} · {dateTime(h.createdAt)}
                  {cart?.customer?.name ? ` · ${cart.customer.name}` : ""}
                </span>
              </button>
              <button className="rounded-lg p-1.5 text-faint hover:text-bad" onClick={() => onDelete(h.id)} aria-label="Delete held bill">
                <Trash2 className="size-4" />
              </button>
            </li>
          );
        })}
      </ul>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ open till */

export function OpenTillPanel({
  tills,
  onOpen,
  busy,
  error,
}: {
  tills: Schemas["TillView"][];
  onOpen: (tillId: number, float: string) => void;
  busy: boolean;
  error: string | null;
}) {
  const [picked, setPicked] = React.useState<number | null>(null);
  const tillId = picked ?? tills[0]?.id ?? null;
  const [float, setFloat] = React.useState("0");
  return (
    <form
      className="glass mx-auto mt-10 max-w-md space-y-4 p-6"
      onSubmit={(e) => {
        e.preventDefault();
        if (tillId && isMoney(float)) onOpen(tillId, float);
      }}
    >
      <div>
        <h1 className="text-lg font-semibold">Open your till</h1>
        <p className="mt-1 text-sm text-muted">Count the cash in the drawer before the first sale. It is checked again when you close.</p>
      </div>
      {tills.length === 0 ? (
        <p className="text-sm text-warn">This branch has no till yet. An owner adds one under Branches and tills.</p>
      ) : (
        <>
          <Field label="Till">
            <Select value={tillId ?? ""} onChange={(e) => setPicked(Number(e.target.value))}>
              {tills.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Cash in the drawer (opening float)" error={error ?? undefined}>
            <AmountInput value={float} onChange={(e) => setFloat(e.target.value)} autoFocus className="text-lg" />
          </Field>
          <Button type="submit" className="w-full" size="lg" loading={busy} disabled={!isMoney(float) || !tillId}>
            Open till
          </Button>
        </>
      )}
    </form>
  );
}

/* ------------------------------------------------------------------ line editor */

export function LineDialog({
  line,
  onClose,
  onSave,
  onRemove,
  branchId,
  canPrice,
}: {
  line: CartLine | null;
  onClose: () => void;
  onSave: (patch: Partial<CartLine>) => void;
  onRemove: () => void;
  branchId: number | null;
  canPrice: boolean;
}) {
  if (!line) return null;
  return <LineBody key={line.key} line={line} onClose={onClose} onSave={onSave} onRemove={onRemove} branchId={branchId} canPrice={canPrice} />;
}

function LineBody({
  line,
  onClose,
  onSave,
  onRemove,
  branchId,
  canPrice,
}: {
  line: CartLine;
  onClose: () => void;
  onSave: (patch: Partial<CartLine>) => void;
  onRemove: () => void;
  branchId: number | null;
  canPrice: boolean;
}) {
  const p = line.product;
  const [qty, setQty] = React.useState(line.qty);
  const [price, setPrice] = React.useState(line.price);
  const [discount, setDiscount] = React.useState(line.discount);
  const [unit, setUnit] = React.useState<number | null>(line.unitId);
  const [batchId, setBatchId] = React.useState<number | null>(line.batchId);
  const [serials, setSerials] = React.useState(line.serials.join("\n"));
  const batches = useQuery({
    queryKey: ["batches", p.id, branchId],
    queryFn: () => api<Schemas["BatchStock"][]>(`/batches?productId=${p.id}${branchId ? `&branchId=${branchId}` : ""}`),
    enabled: p.kind === "BATCH",
  });
  const units = [{ unitId: null as number | null, name: p.unitName, price: p.price, factor: "1" }, ...p.units];
  const qtyOk = p.kind === "SERIAL" || (p.decimals ? /^\d+(\.\d{1,3})?$/ : /^\d+$/).test(qty);
  const discOk = parseDiscount(discount) !== null;
  const priceOk = isMoney(price);
  const below = p.minPrice && priceOk && unit === null && toPaisa(price) < toPaisa(p.minPrice);
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={p.name}
      description={`${p.sku}${p.stock ? ` · in stock ${fmtQty(p.stock)} ${p.unitName}` : ""}`}
      footer={
        <>
          <Button variant="danger" onClick={onRemove}>
            <Trash2 /> Remove
          </Button>
          <Button type="submit" form="line-form" disabled={!qtyOk || !discOk || !priceOk}>
            Save
          </Button>
        </>
      }
    >
      <form
        id="line-form"
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          const chosen = units.find((u) => u.unitId === unit) ?? units[0];
          const listPrice = chosen.price;
          const batch = batches.data?.find((b) => b.batchId === batchId);
          onSave({
            qty,
            price,
            listPrice,
            discount,
            unitId: unit,
            unitName: chosen.name,
            batchId,
            batchLabel: batch ? `${batch.batchNo} · exp ${date(batch.expiry)}` : null,
            serials: p.kind === "SERIAL" ? serials.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean) : line.serials,
          });
        }}
      >
        {p.kind === "SERIAL" ? (
          <Field label="IMEI / serial numbers" className="col-span-2" hint="One per line; scan each phone.">
            <textarea className="field min-h-24 font-mono text-sm" value={serials} onChange={(e) => setSerials(e.target.value)} autoFocus />
          </Field>
        ) : (
          <Field label={`Quantity (${units.find((u) => u.unitId === unit)?.name ?? p.unitName})`} error={qtyOk ? undefined : p.decimals ? "Up to 3 decimals" : "Whole numbers only"}>
            <AmountInput value={qty} onChange={(e) => setQty(e.target.value)} autoFocus className="text-lg" />
          </Field>
        )}
        {units.length > 1 && p.kind !== "SERIAL" && (
          <Field label="Unit">
            <Select
              value={unit ?? ""}
              onChange={(e) => {
                const id = e.target.value ? Number(e.target.value) : null;
                setUnit(id);
                setPrice((units.find((u) => u.unitId === id) ?? units[0]).price);
              }}
            >
              {units.map((u) => (
                <option key={u.unitId ?? "base"} value={u.unitId ?? ""}>
                  {u.name}
                  {u.factor !== "1" ? ` (${fmtQty(u.factor)} ${p.unitName})` : ""}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="Price" hint={canPrice ? (below ? `Below the lowest price ${money(p.minPrice)} — needs a manager` : undefined) : "A price change asks for a manager's PIN"}>
          <AmountInput value={price} onChange={(e) => setPrice(e.target.value)} />
        </Field>
        <Field label="Discount" hint='Amount, or percent like "10%"' error={discOk ? undefined : "Not a valid discount"}>
          <Input value={discount} onChange={(e) => setDiscount(e.target.value)} placeholder="0" className="text-right font-mono" />
        </Field>
        {p.kind === "BATCH" && (
          <Field label="Batch" className="col-span-2" hint="Leave on automatic to sell the earliest expiry first.">
            <Select value={batchId ?? ""} onChange={(e) => setBatchId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">Automatic (earliest expiry first)</option>
              {batches.data
                ?.filter((b) => toPaisa(b.qty) > BigInt(0))
                .map((b) => (
                  <option key={b.batchId} value={b.batchId} disabled={b.expired}>
                    {b.batchNo} · exp {date(b.expiry)} · {fmtQty(b.qty)} left{b.expired ? " · expired" : ""}
                  </option>
                ))}
            </Select>
          </Field>
        )}
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ IMEI prompt */

export function SerialPrompt({ product, onClose, onAdd }: { product: { name: string } | null; onClose: () => void; onAdd: (serial: string) => void }) {
  const [serial, setSerial] = React.useState("");
  if (!product) return null;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`IMEI for ${product.name}`} description="Scan or type the IMEI / serial of the unit being sold.">
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (serial.trim()) {
            onAdd(serial.trim());
            setSerial("");
          }
        }}
      >
        <Input value={serial} onChange={(e) => setSerial(e.target.value)} autoFocus className="font-mono text-lg" aria-label="IMEI" />
        <Button type="submit" className="w-full" disabled={!serial.trim()}>
          Add
        </Button>
      </form>
    </Dialog>
  );
}

/** Sum of a list of money strings. */
export const sum = (values: string[]) => values.reduce((s, v) => addMoney(s, v), ZERO);

export function KeyHint({ k, children, className }: { k: string; children: React.ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1", className)}>
      {children}
      <kbd className="hidden rounded border border-line px-1 font-mono text-[10px] text-faint lg:inline">{k}</kbd>
    </span>
  );
}

/** The other payments after points take `amount`: the first one is reduced by it (never below zero). */
function subtractFrom(payments: Payment[], amount: string): Payment[] {
  if (!payments.length) return payments;
  const [first, ...rest] = payments;
  const left = toPaisa(first.amount) - toPaisa(amount);
  return [{ ...first, amount: left > BigInt(0) ? fromPaisa(left) : ZERO }, ...rest];
}
