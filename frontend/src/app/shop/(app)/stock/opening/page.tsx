"use client";

import { useMutation } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Field, Select } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { DocLines, lineValue, serialList, useDocLines } from "@/features/documents/doc-lines";
import { api, errorMessage } from "@/lib/api/client";
import { money } from "@/lib/format";
import { addMoney, ZERO } from "@/lib/money";
import { useBranch, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";

/** Opening stock (STK-07): what is on the shelves on day one, at its cost. */
export default function OpeningStockPage() {
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const [branchId, setBranchId] = React.useState<number | null>(null);
  const doc = useDocLines();
  const where = branchId ?? branch?.id ?? null;
  const value = doc.lines.reduce((s, l) => addMoney(s, lineValue(l, "cost")), ZERO);
  const save = useMutation({
    mutationFn: () =>
      api("/opening-stock", {
        method: "POST",
        body: {
          branchId: where,
          lines: doc.lines.map((l) => ({
            productId: l.product.id,
            qty: l.product.kind === "SERIAL" ? null : l.qty,
            unitCost: l.cost || null,
            batchNo: l.batchNo || null,
            expiry: l.expiry || null,
            serials: l.product.kind === "SERIAL" ? serialList(l.serials).map((serial) => ({ serial })) : null,
          })),
        },
      }),
    onSuccess: () => {
      toast.success("Opening stock saved");
      router.push("/stock");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader
        back={{ href: toHref("/stock"), label: "Stock" }}
        title="Opening stock"
        description="Enter what you already have, with what it cost you. Use this once when you start; afterwards stock comes in through purchases."
      />
      <div className="space-y-4">
        {shop.branches.length > 1 && (
          <Panel>
            <Field label="Branch" className="max-w-xs">
              <Select value={where ?? ""} onChange={(e) => setBranchId(Number(e.target.value))}>
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
          </Panel>
        )}
        <Panel title="Items" description="For many products at once, use Import from Excel with the opening stock column.">
          <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} config={{ cost: true, batch: "new", serials: "new", kinds: ["STANDARD", "BATCH", "SERIAL"] }} />
        </Panel>
        <div className="flex items-center justify-end gap-4">
          <span className="text-sm text-muted">
            Value <span className="font-mono text-lg text-ink">{money(value)}</span>
          </span>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!doc.lines.length}>
            Save opening stock
          </Button>
        </div>
      </div>
    </>
  );
}
