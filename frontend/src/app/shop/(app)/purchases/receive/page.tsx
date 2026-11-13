"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AmountInput, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel, Skeleton } from "@/components/ui/layout";
import { DocLines, lineQty, lineValue, newLine, serialList, useDocLines, type Product } from "@/features/documents/doc-lines";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { money, qty as fmtQty } from "@/lib/format";
import { addMoney, isMoney, subMoney, toMilli, ZERO } from "@/lib/money";
import { useBranch, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";
import { todayIso } from "@/components/list-page";
import { useQueryParam } from "@/lib/use-browser-value";

/** Receive goods (PUR-03): from an order or without one; batches with expiry, IMEIs, bonus, freight, paying now. */
export default function ReceiveGoodsPage() {
  const poParam = useQueryParam("po");
  const supplierParam = useQueryParam("supplier");
  const po = useQuery({ queryKey: ["po", poParam], queryFn: () => api<Schemas["PoView"]>(`/purchase-orders/${poParam}`), enabled: Boolean(poParam) });
  const supplier = useQuery({ queryKey: ["supplier", supplierParam], queryFn: () => api<Schemas["SupplierView"]>(`/suppliers/${supplierParam}`), enabled: Boolean(supplierParam) });
  if ((poParam && po.isPending) || (supplierParam && supplier.isPending)) return <Skeleton className="h-96" />;
  return <ReceiveForm key={poParam ?? supplierParam ?? "new"} po={po.data ?? null} supplier={supplier.data ?? null} />;
}

function ReceiveForm({ po, supplier: presetSupplier }: { po: Schemas["PoView"] | null; supplier: Schemas["SupplierView"] | null }) {
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const client = useQueryClient();
  const [branch] = useBranch();
  const doc = useDocLines();
  const [supplier, setSupplier] = React.useState<PickedParty | null>(
    po ? { id: po.supplierId, name: po.supplierName } : presetSupplier ? { id: presetSupplier.id, name: presetSupplier.name } : null,
  );
  const [branchId, setBranchId] = React.useState<number | null>(po?.branchId ?? null);
  const [billNo, setBillNo] = React.useState("");
  const [billDate, setBillDate] = React.useState(todayIso());
  const [freight, setFreight] = React.useState("");
  const [tax, setTax] = React.useState("");
  const [discount, setDiscount] = React.useState("");
  const [paidNow, setPaidNow] = React.useState("");
  const [paidMethod, setPaidMethod] = React.useState("CASH");
  const [note, setNote] = React.useState("");
  const [key] = React.useState(() => crypto.randomUUID());
  const [loaded, setLoaded] = React.useState(!po);

  // Fill the lines still to come from the order.
  React.useEffect(() => {
    if (!po) return;
    let cancelled = false;
    void (async () => {
      const open = po.lines.filter((l) => toMilli(l.qtyOpen) > BigInt(0));
      const products = await Promise.all(
        open.map((l) => client.fetchQuery({ queryKey: ["product", l.productId], queryFn: () => api<Product>(`/products/${l.productId}`), staleTime: 60_000 })),
      );
      if (cancelled) return;
      doc.setLines(
        open.map((l, i) =>
          newLine(products[i], {
            unitId: l.unitId,
            qty: products[i].kind === "SERIAL" ? "" : trim(l.qtyOpen),
            cost: l.unitCost,
            poLineId: l.id,
            maxQty: l.qtyOpen,
          }),
        ),
      );
      setLoaded(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [po]); // eslint-disable-line react-hooks/exhaustive-deps

  const subtotal = doc.lines.reduce((s, l) => addMoney(s, lineValue(l, "cost")), ZERO);
  const ok = (v: string) => !v || isMoney(v);
  const total = subMoney(addMoney(subtotal, ok(freight) ? freight : ZERO, ok(tax) ? tax : ZERO), ok(discount) && discount ? discount : ZERO);
  const missing = doc.lines.find(
    (l) => (l.product.kind === "SERIAL" && serialList(l.serials).length === 0) || (l.product.kind === "BATCH" && (!l.batchNo.trim() || !l.expiry)) || !isMoney(l.cost || "x"),
  );

  const save = useMutation({
    mutationFn: () =>
      api<Schemas["GrnView"]>("/grns", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: {
          supplierId: supplier?.id,
          branchId: branchId ?? branch?.id,
          poId: po?.id ?? null,
          supplierBillNo: billNo || null,
          billDate: billDate || null,
          freight: freight || null,
          tax: tax || null,
          discount: discount || null,
          paidNow: paidNow || null,
          paidMethod: paidNow ? paidMethod : null,
          note: note || null,
          lines: doc.lines.map((l) => ({
            poLineId: l.poLineId ?? null,
            productId: l.product.id,
            unitId: l.unitId,
            qty: l.product.kind === "SERIAL" ? lineQty(l) : l.qty,
            bonusQty: l.bonus || null,
            unitCost: l.cost,
            discount: l.discount || null,
            retailPrice: l.price !== l.product.price ? l.price : null,
            batchNo: l.product.kind === "BATCH" ? l.batchNo : null,
            expiry: l.product.kind === "BATCH" ? l.expiry : null,
            serials: l.product.kind === "SERIAL" ? serialList(l.serials).map((serial) => ({ serial })) : null,
          })),
        },
      }),
    onSuccess: (grn) => {
      toast.success(`Goods received: ${grn.number}`);
      client.invalidateQueries({ queryKey: ["grns"] });
      client.invalidateQueries({ queryKey: ["po"] });
      router.push(`/purchases/grns/${grn.id}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const fieldErr = (f: string) => (save.error instanceof ApiError ? save.error.field(f) : undefined);

  return (
    <>
      <PageHeader
        back={{ href: toHref(po ? `/purchases/${po.id}` : "/purchases"), label: po ? po.number : "Purchasing" }}
        title="Receive goods"
        description={po ? `Against order ${po.number} from ${po.supplierName}` : "Enter the supplier's bill. Stock and the average cost update when you save."}
      />
      <div className="space-y-4">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-4">
            <Field label="Supplier" required error={fieldErr("supplierId")}>
              {po ? <Input value={po.supplierName} disabled /> : <PartyPicker kind="supplier" value={supplier} onChange={setSupplier} />}
            </Field>
            {shop.branches.length > 1 && (
              <Field label="Received at">
                <Select value={branchId ?? branch?.id ?? ""} onChange={(e) => setBranchId(Number(e.target.value))} disabled={Boolean(po)}>
                  {shop.branches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name}
                    </option>
                  ))}
                </Select>
              </Field>
            )}
            <Field label="Supplier's bill no.">
              <Input value={billNo} onChange={(e) => setBillNo(e.target.value)} />
            </Field>
            <Field label="Bill date">
              <Input type="date" value={billDate} onChange={(e) => setBillDate(e.target.value)} />
            </Field>
          </div>
        </Panel>
        <Panel title="Items" description="Cost is per unit before the line discount. Change the price to update the selling price too.">
          {!loaded ? (
            <Skeleton className="h-32" />
          ) : (
            <DocLines
              lines={doc.lines}
              update={doc.update}
              remove={doc.remove}
              onAdd={doc.add}
              config={{ unit: true, cost: true, price: true, bonus: true, discount: true, batch: "new", serials: "new", kinds: ["STANDARD", "BATCH", "SERIAL"] }}
            />
          )}
          {doc.lines.some((l) => l.maxQty && toMilli(l.qty || "0") > toMilli(l.maxQty)) && (
            <p className="mt-2 text-sm text-warn">More than ordered on some lines — the extra is received too.</p>
          )}
        </Panel>
        <Panel>
          <div className="grid gap-4 sm:grid-cols-4">
            <Field label="Freight / loading" error={fieldErr("freight")}>
              <AmountInput value={freight} onChange={(e) => setFreight(e.target.value)} placeholder="0" />
            </Field>
            <Field label="Tax on the bill" error={fieldErr("tax")}>
              <AmountInput value={tax} onChange={(e) => setTax(e.target.value)} placeholder="0" />
            </Field>
            <Field label="Bill discount" error={fieldErr("discount")}>
              <AmountInput value={discount} onChange={(e) => setDiscount(e.target.value)} placeholder="0" />
            </Field>
            <div className="rounded-xl bg-raised/60 p-3 text-right">
              <span className="block text-xs text-muted">Bill total</span>
              <span className="font-mono text-xl font-semibold">{money(total)}</span>
            </div>
            <Field label="Paid now" hint="The rest goes on the supplier's account." error={fieldErr("paidNow")}>
              <AmountInput value={paidNow} onChange={(e) => setPaidNow(e.target.value)} placeholder="0" />
            </Field>
            <Field label="Paid by">
              <Select value={paidMethod} onChange={(e) => setPaidMethod(e.target.value)} disabled={!paidNow}>
                <option value="CASH">Cash</option>
                <option value="BANK">Bank transfer</option>
                <option value="JAZZCASH">JazzCash</option>
                <option value="EASYPAISA">Easypaisa</option>
              </Select>
            </Field>
            <Field label="Note" className="sm:col-span-2">
              <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
            </Field>
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-end gap-3">
            {missing && (
              <span className="text-sm text-warn">
                {missing.product.name}: {missing.product.kind === "SERIAL" ? "scan the IMEIs" : missing.product.kind === "BATCH" ? "batch no. and expiry" : "enter the cost"}
              </span>
            )}
            <span className="text-sm text-muted">{doc.lines.length} lines · {fmtQty(String(doc.lines.reduce((n, l) => n + Number(lineQty(l) || 0), 0)))} units</span>
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!supplier || !doc.lines.length || Boolean(missing)}>
              Save and add to stock
            </Button>
          </div>
        </Panel>
      </div>
    </>
  );
}

function trim(q: string) {
  return q.includes(".") ? q.replace(/0+$/, "").replace(/\.$/, "") : q;
}
