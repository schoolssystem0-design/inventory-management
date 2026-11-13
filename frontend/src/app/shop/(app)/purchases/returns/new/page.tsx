"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { DocLines, lineQty, lineValue, serialList, useDocLines } from "@/features/documents/doc-lines";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money, qty } from "@/lib/format";
import { addMoney, fromMilli, isQty, isZero, lineAmount, toMilli, ZERO } from "@/lib/money";
import { useBranch, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";
import { useQueryParam } from "@/lib/use-browser-value";

const REASONS = [
  { value: "DAMAGED", label: "Damaged" },
  { value: "EXPIRED", label: "Expired" },
  { value: "WRONG", label: "Wrong item" },
  { value: "EXCESS", label: "Too many" },
  { value: "OTHER", label: "Other" },
];

/** Return to supplier (PUR-05): from a bill (its lines and cost) or any stock. */
export default function NewPurchaseReturnPage() {
  const grnParam = useQueryParam("grn");
  const grn = useQuery({ queryKey: ["grn", grnParam], queryFn: () => api<Schemas["GrnView"]>(`/grns/${grnParam}`), enabled: Boolean(grnParam) });
  if (grnParam && grn.isPending) return <Skeleton className="h-96" />;
  return <ReturnForm key={grnParam ?? "free"} grn={grn.data ?? null} />;
}

function ReturnForm({ grn }: { grn: Schemas["GrnView"] | null }) {
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const doc = useDocLines();
  const [supplier, setSupplier] = React.useState<PickedParty | null>(grn ? { id: grn.supplierId, name: grn.supplierName } : null);
  const [branchId, setBranchId] = React.useState<number | null>(grn?.branchId ?? null);
  const [reason, setReason] = React.useState("DAMAGED");
  const [note, setNote] = React.useState("");
  const [qtys, setQtys] = React.useState<Record<number, string>>({});
  const [serials, setSerials] = React.useState<Record<number, string>>({});

  const fromBill = grn
    ? grn.lines
        .map((l) => {
          const left = fromMilli(toMilli(l.qty) + toMilli(l.bonusQty ?? "0") - toMilli(l.qtyReturned));
          return { line: l, left };
        })
        .filter((x) => toMilli(x.left) > BigInt(0))
    : [];
  const billValue = fromBill.reduce((s, { line }) => {
    const q = line.serials?.length ? String(serialList(serials[line.id] ?? "").length) : qtys[line.id];
    return q && isQty(q) ? addMoney(s, lineAmount(q, line.unitCost)) : s;
  }, ZERO);
  const freeValue = doc.lines.reduce((s, l) => addMoney(s, lineValue(l, "cost")), ZERO);

  const save = useMutation({
    mutationFn: () => {
      const lines = grn
        ? fromBill
            .map(({ line }) => {
              const sn = line.serials?.length ? serialList(serials[line.id] ?? "") : null;
              const q = sn ? String(sn.length) : qtys[line.id];
              return { grnLineId: line.id, productId: line.productId, qty: q, serials: sn };
            })
            .filter((l) => l.qty && toMilli(l.qty) > BigInt(0))
        : doc.lines.map((l) => ({
            productId: l.product.id,
            qty: lineQty(l),
            unitCost: l.cost || null,
            batchId: l.batchId,
            serials: l.product.kind === "SERIAL" ? serialList(l.serials) : null,
          }));
      return api<Schemas["PurchaseReturnView"]>("/purchase-returns", {
        method: "POST",
        body: { supplierId: supplier?.id, branchId: branchId ?? branch?.id, grnId: grn?.id ?? null, reason, note: note || null, lines },
      });
    },
    onSuccess: (r) => {
      toast.success(`Return ${r.number} saved`);
      router.push(`/purchases/returns/${r.id}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <PageHeader
        back={{ href: toHref(grn ? `/purchases/grns/${grn.id}` : "/purchases/returns"), label: grn ? grn.number : "Purchase returns" }}
        title="Return to supplier"
        description={grn ? `Items from ${grn.number} (${grn.supplierName})` : "Stock goes out at the cost you enter; the supplier owes you that much less."}
      />
      <div className="space-y-4">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Supplier" required>
              {grn ? <Input value={grn.supplierName} disabled /> : <PartyPicker kind="supplier" value={supplier} onChange={setSupplier} />}
            </Field>
            {shop.branches.length > 1 && (
              <Field label="From branch">
                <Select value={branchId ?? branch?.id ?? ""} onChange={(e) => setBranchId(Number(e.target.value))} disabled={Boolean(grn)}>
                  {shop.branches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name}
                    </option>
                  ))}
                </Select>
              </Field>
            )}
            <Field label="Reason">
              <Select value={reason} onChange={(e) => setReason(e.target.value)}>
                {REASONS.map((r) => (
                  <option key={r.value} value={r.value}>
                    {r.label}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
        </Panel>
        <Panel title="Items">
          {grn ? (
            <Table>
              <thead>
                <tr>
                  <Th>Item</Th>
                  <Th align="right">Can return</Th>
                  <Th align="right">Cost</Th>
                  <Th align="right">Return</Th>
                </tr>
              </thead>
              <tbody>
                {fromBill.map(({ line, left }) => (
                  <tr key={line.id} className="align-top">
                    <Td>
                      {line.productName}
                      {line.batchNo && <span className="block text-xs text-faint">Batch {line.batchNo}</span>}
                    </Td>
                    <Td align="right">{qty(left)}</Td>
                    <Td align="right">{money(line.unitCost, false)}</Td>
                    <Td align="right">
                      {line.serials?.length ? (
                        <textarea
                          className="field min-h-14 w-56 font-mono text-xs"
                          value={serials[line.id] ?? ""}
                          onChange={(e) => setSerials((s) => ({ ...s, [line.id]: e.target.value }))}
                          placeholder={line.serials.slice(0, 2).join("\n")}
                          aria-label={`IMEIs to return of ${line.productName}`}
                        />
                      ) : (
                        <Input
                          value={qtys[line.id] ?? ""}
                          onChange={(e) => setQtys((q) => ({ ...q, [line.id]: e.target.value }))}
                          inputMode="decimal"
                          className="ml-auto w-24 text-right font-mono"
                          placeholder="0"
                          aria-label={`Return quantity of ${line.productName}`}
                        />
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ) : (
            <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} branchId={branchId ?? branch?.id} config={{ cost: true, batch: "pick", serials: "existing", kinds: ["STANDARD", "BATCH", "SERIAL"] }} />
          )}
        </Panel>
        <Panel>
          <Field label="Note">
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
          </Field>
          <div className="mt-4 flex items-center justify-end gap-4">
            <span className="text-sm text-muted">
              Value <span className="font-mono text-lg text-ink">{money(grn ? billValue : freeValue)}</span>
            </span>
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!supplier || (grn ? isZero(billValue) : !doc.lines.length)}>
              Save return
            </Button>
          </div>
        </Panel>
      </div>
    </>
  );
}
