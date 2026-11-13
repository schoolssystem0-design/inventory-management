"use client";

import { useMutation } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AmountInput, Field, Input, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { DocLines, lineValue, useDocLines } from "@/features/documents/doc-lines";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { addMoney, ZERO } from "@/lib/money";
import { useBranch, useShopHref, useShopRouter } from "@/lib/shop-context";
import { daysAgoIso } from "@/components/list-page";

export default function NewQuotationPage() {
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const doc = useDocLines();
  const [customer, setCustomer] = React.useState<PickedParty | null>(null);
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  const [validUntil, setValidUntil] = React.useState(daysAgoIso(-15));
  const [discount, setDiscount] = React.useState("");
  const [note, setNote] = React.useState("");
  const subtotal = doc.lines.reduce((s, l) => addMoney(s, lineValue(l, "price")), ZERO);
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["QuotationView"]>("/quotations", {
        method: "POST",
        body: {
          branchId: branch?.id,
          customerId: customer?.id ?? null,
          customerName: customer ? null : name || null,
          customerPhone: customer ? null : phone || null,
          validUntil,
          billDiscount: discount || null,
          note: note || null,
          lines: doc.lines.map((l) => ({ productId: l.product.id, unitId: l.unitId, qty: l.qty, price: l.price, discount: l.discount || null })),
        },
      }),
    onSuccess: (q) => {
      toast.success(`Quotation ${q.number} saved`);
      router.push(`/quotations/${q.id}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader back={{ href: toHref("/quotations"), label: "Quotations" }} title="New quotation" />
      <div className="space-y-4">
        <Panel title="Customer">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Existing customer">
              <PartyPicker kind="customer" value={customer} onChange={setCustomer} />
            </Field>
            {!customer && (
              <>
                <Field label="Or name">
                  <Input value={name} onChange={(e) => setName(e.target.value)} />
                </Field>
                <Field label="Phone">
                  <Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" />
                </Field>
              </>
            )}
          </div>
        </Panel>
        <Panel title="Items">
          <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} config={{ unit: true, price: true, discount: true, kinds: ["STANDARD", "BATCH", "SERIAL", "BUNDLE", "SERVICE"] }} />
        </Panel>
        <Panel>
          <div className="grid gap-4 sm:grid-cols-4">
            <Field label="Valid until">
              <Input type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
            </Field>
            <Field label="Bill discount">
              <AmountInput value={discount} onChange={(e) => setDiscount(e.target.value)} placeholder="0" />
            </Field>
            <Field label="Note" className="sm:col-span-2">
              <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
            </Field>
          </div>
          <div className="mt-4 flex items-center justify-end gap-4">
            <span className="text-sm text-muted">
              Items <span className="font-mono text-ink">{money(subtotal)}</span> (tax is added when saved)
            </span>
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!doc.lines.length}>
              Save quotation
            </Button>
          </div>
        </Panel>
      </div>
    </>
  );
}
