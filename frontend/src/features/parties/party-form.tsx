"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { label, money } from "@/lib/format";

type Kind = "customer" | "supplier";
type Customer = Schemas["CustomerView"];
type Supplier = Schemas["SupplierView"];

/** Add or edit a customer or supplier. Opening balance only when adding. */
export function PartyDialog({
  kind,
  existing,
  onClose,
  onSaved,
}: {
  kind: Kind;
  existing?: Customer | Supplier | null;
  onClose: () => void;
  onSaved?: (id: number) => void;
}) {
  const client = useQueryClient();
  const c = existing as Partial<Customer & Supplier> | null | undefined;
  const [form, setForm] = React.useState({
    name: c?.name ?? "",
    phone: c?.phone ?? "",
    email: c?.email ?? "",
    address: c?.address ?? "",
    notes: c?.notes ?? "",
    cnic: c?.cnic ?? "",
    kind: c?.kind ?? "RETAIL",
    creditLimit: c?.creditLimit ?? "",
    company: c?.company ?? "",
    ntn: c?.ntn ?? "",
    termsDays: c?.termsDays != null ? String(c.termsDays) : "",
    openingBalance: "",
  });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));
  const save = useMutation({
    mutationFn: () => {
      const common = {
        name: form.name,
        phone: form.phone || null,
        email: form.email || null,
        address: form.address || null,
        notes: form.notes || null,
        version: c?.version,
      };
      const opening = existing ? undefined : form.openingBalance || null;
      const body =
        kind === "customer"
          ? { ...common, cnic: form.cnic || null, kind: form.kind, creditLimit: form.creditLimit || null, openingBalance: opening }
          : { ...common, company: form.company || null, ntn: form.ntn || null, termsDays: form.termsDays ? Number(form.termsDays) : null, openingBalance: opening };
      const path = kind === "customer" ? "/customers" : "/suppliers";
      return existing
        ? api<{ id: number }>(`${path}/${existing.id}`, { method: "PATCH", body })
        : api<{ id: number }>(path, { method: "POST", body });
    },
    onSuccess: (saved) => {
      toast.success(existing ? "Saved" : `${kind === "customer" ? "Customer" : "Supplier"} added`);
      client.invalidateQueries({ queryKey: [kind === "customer" ? "customers" : "suppliers"] });
      client.invalidateQueries({ queryKey: [kind, String(existing?.id ?? saved.id)] });
      onSaved?.(saved.id);
      onClose();
    },
  });
  const err = (f: string) => (save.error instanceof ApiError ? save.error.field(f) : undefined);
  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title={existing ? `Edit ${existing.name}` : kind === "customer" ? "New customer" : "New supplier"}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="party-form" loading={save.isPending}>
            Save
          </Button>
        </>
      }
    >
      <form
        id="party-form"
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Name" required error={err("name")}>
          <Input value={form.name} onChange={set("name")} autoFocus />
        </Field>
        <Field label="Phone" error={err("phone")}>
          <Input value={form.phone} onChange={set("phone")} inputMode="tel" placeholder="03xx xxxxxxx" />
        </Field>
        {kind === "supplier" && (
          <>
            <Field label="Company" error={err("company")}>
              <Input value={form.company} onChange={set("company")} />
            </Field>
            <Field label="Payment terms (days)" error={err("termsDays")}>
              <Input value={form.termsDays} onChange={set("termsDays")} inputMode="numeric" />
            </Field>
            <Field label="NTN" error={err("ntn")}>
              <Input value={form.ntn} onChange={set("ntn")} />
            </Field>
          </>
        )}
        {kind === "customer" && (
          <>
            <Field label="Type">
              <Select value={form.kind} onChange={set("kind")}>
                <option value="RETAIL">Retail</option>
                <option value="WHOLESALE">Wholesale</option>
              </Select>
            </Field>
            <Field label="Credit limit" hint="Empty = no limit. Selling above it needs a manager." error={err("creditLimit")}>
              <AmountInput value={form.creditLimit} onChange={set("creditLimit")} />
            </Field>
            <Field label="CNIC" error={err("cnic")}>
              <Input value={form.cnic} onChange={set("cnic")} placeholder="xxxxx-xxxxxxx-x" />
            </Field>
          </>
        )}
        <Field label="Email" error={err("email")}>
          <Input value={form.email} onChange={set("email")} type="email" />
        </Field>
        {!existing && (
          <Field label={kind === "customer" ? "Already owes you (opening balance)" : "You already owe them (opening balance)"} error={err("openingBalance")}>
            <AmountInput value={form.openingBalance} onChange={set("openingBalance")} placeholder="0" />
          </Field>
        )}
        <Field label="Address" className="sm:col-span-2" error={err("address")}>
          <Input value={form.address} onChange={set("address")} />
        </Field>
        <Field label="Notes" className="sm:col-span-2">
          <Textarea value={form.notes} onChange={set("notes")} className="min-h-12" />
        </Field>
        {save.error && !(save.error instanceof ApiError && save.error.fieldErrors.length) && (
          <p className="text-sm text-bad sm:col-span-2">{errorMessage(save.error)}</p>
        )}
      </form>
    </Dialog>
  );
}

/** Receive money from a customer or pay a supplier; the payment clears the oldest bills first. */
export function PaymentEntryDialog({
  kind,
  partyId,
  partyName,
  balance,
  branchId,
  tillSessionId,
  onClose,
}: {
  kind: Kind;
  partyId: number;
  partyName: string;
  balance: string;
  branchId?: number | null;
  tillSessionId?: number | null;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const [amount, setAmount] = React.useState(balance.startsWith("-") || balance === "0.00" ? "" : balance);
  const [method, setMethod] = React.useState("CASH");
  const [reference, setReference] = React.useState("");
  const [note, setNote] = React.useState("");
  const [key] = React.useState(() => crypto.randomUUID());
  const pay = useMutation({
    mutationFn: () => {
      const body =
        kind === "customer"
          ? { customerId: partyId, amount, method, reference: reference || null, note: note || null, tillSessionId }
          : { supplierId: partyId, amount, method, reference: reference || null, note: note || null, branchId };
      return api(kind === "customer" ? "/customer-payments" : "/supplier-payments", { method: "POST", body, headers: { "Idempotency-Key": key } });
    },
    onSuccess: () => {
      toast.success(kind === "customer" ? "Payment received" : "Payment recorded");
      client.invalidateQueries({ queryKey: [kind, String(partyId)] });
      client.invalidateQueries({ queryKey: [`${kind}-ledger`] });
      client.invalidateQueries({ queryKey: [kind === "customer" ? "customers" : "suppliers"] });
      onClose();
    },
  });
  const methods = kind === "customer" ? ["CASH", "CARD", "JAZZCASH", "EASYPAISA", "BANK"] : ["CASH", "BANK", "JAZZCASH", "EASYPAISA"];
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={kind === "customer" ? `Receive from ${partyName}` : `Pay ${partyName}`}
      description={`Balance ${money(balance)}. The payment clears the oldest bills first.`}
      footer={
        <Button type="submit" form="pay-party" loading={pay.isPending} disabled={!/^\d+(\.\d{1,2})?$/.test(amount)}>
          {kind === "customer" ? "Receive" : "Pay"}
        </Button>
      }
    >
      <form
        id="pay-party"
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          pay.mutate();
        }}
      >
        <Field label="Amount" error={pay.error instanceof ApiError ? pay.error.field("amount") : undefined}>
          <AmountInput value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus className="text-lg" />
        </Field>
        <Field label="Method">
          <Select value={method} onChange={(e) => setMethod(e.target.value)}>
            {methods.map((m) => (
              <option key={m} value={m}>
                {label(m)}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Reference">
          <Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Cheque / txn no." />
        </Field>
        <Field label="Note">
          <Input value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        {pay.error && <p className="text-sm text-bad sm:col-span-2">{errorMessage(pay.error)}</p>}
      </form>
    </Dialog>
  );
}

/** Running ledger table shared by customers and suppliers. */
export function LedgerTable({
  lines,
  debitLabel,
  creditLabel,
  docHref,
}: {
  lines: Schemas["LedgerLine"][];
  debitLabel: string;
  creditLabel: string;
  docHref?: (line: Schemas["LedgerLine"]) => string | null;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] text-left text-sm">
        <thead>
          <tr className="text-xs text-faint">
            <th className="border-b border-line px-4 py-2.5 font-medium">Date</th>
            <th className="border-b border-line px-4 py-2.5 font-medium">Details</th>
            <th className="border-b border-line px-4 py-2.5 text-right font-medium">{debitLabel}</th>
            <th className="border-b border-line px-4 py-2.5 text-right font-medium">{creditLabel}</th>
            <th className="border-b border-line px-4 py-2.5 text-right font-medium">Balance</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => {
            const href = docHref?.(l);
            return (
              <tr key={l.id}>
                <td className="border-b border-line px-4 py-2.5 text-muted">{l.date}</td>
                <td className="border-b border-line px-4 py-2.5">
                  {href ? (
                    <a href={href} className="hover:text-aqua">
                      {l.description}
                    </a>
                  ) : (
                    l.description
                  )}
                  {l.docNumber && <span className="ml-2 font-mono text-xs text-faint">{l.docNumber}</span>}
                </td>
                <td className="border-b border-line px-4 py-2.5 text-right font-mono">{l.debit !== "0.00" ? money(l.debit, false) : ""}</td>
                <td className="border-b border-line px-4 py-2.5 text-right font-mono">{l.credit !== "0.00" ? money(l.credit, false) : ""}</td>
                <td className="border-b border-line px-4 py-2.5 text-right font-mono">{money(l.balanceAfter, false)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
