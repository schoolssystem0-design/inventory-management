"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { DocLines, lineValue, useDocLines } from "@/features/documents/doc-lines";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { addMoney, ZERO } from "@/lib/money";
import { useBranch, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";
import { useQueryParam } from "@/lib/use-browser-value";

export default function NewPurchaseOrderPage() {
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const supplierParam = useQueryParam("supplier");
  const preset = useQuery({
    queryKey: ["supplier", supplierParam],
    queryFn: () => api<Schemas["SupplierView"]>(`/suppliers/${supplierParam}`),
    enabled: Boolean(supplierParam),
  });
  const [picked, setPicked] = React.useState<PickedParty | null | undefined>(undefined);
  const supplier = picked === undefined ? (preset.data ? { id: preset.data.id, name: preset.data.name, phone: preset.data.phone } : null) : picked;
  const [branchId, setBranchId] = React.useState<number | null>(null);
  const [expectedOn, setExpectedOn] = React.useState("");
  const [note, setNote] = React.useState("");
  const doc = useDocLines();
  const total = doc.lines.reduce((s, l) => addMoney(s, lineValue(l, "cost")), ZERO);
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["PoView"]>("/purchase-orders", {
        method: "POST",
        body: {
          supplierId: supplier?.id,
          branchId: branchId ?? branch?.id,
          expectedOn: expectedOn || null,
          note: note || null,
          lines: doc.lines.map((l) => ({ productId: l.product.id, unitId: l.unitId, qty: l.qty, unitCost: l.cost || null })),
        },
      }),
    onSuccess: (po) => {
      toast.success(`Order ${po.number} saved`);
      router.push(`/purchases/${po.id}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader back={{ href: toHref("/purchases"), label: "Purchasing" }} title="New purchase order" />
      <div className="space-y-4">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Supplier" required>
              <PartyPicker kind="supplier" value={supplier} onChange={setPicked} />
            </Field>
            {shop.branches.length > 1 && (
              <Field label="Deliver to">
                <Select value={branchId ?? branch?.id ?? ""} onChange={(e) => setBranchId(Number(e.target.value))}>
                  {shop.branches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name}
                    </option>
                  ))}
                </Select>
              </Field>
            )}
            <Field label="Expected on">
              <Input type="date" value={expectedOn} onChange={(e) => setExpectedOn(e.target.value)} />
            </Field>
          </div>
        </Panel>
        <Panel title="Items">
          <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} config={{ unit: true, cost: true, kinds: ["STANDARD", "BATCH", "SERIAL"] }} />
        </Panel>
        <Panel>
          <Field label="Note">
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
          </Field>
          <div className="mt-4 flex items-center justify-end gap-4">
            <span className="text-sm text-muted">
              Total <span className="font-mono text-lg text-ink">{money(total)}</span>
            </span>
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!supplier || !doc.lines.length}>
              Save order
            </Button>
          </div>
        </Panel>
      </div>
    </>
  );
}
