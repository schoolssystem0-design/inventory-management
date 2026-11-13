"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ClipboardList } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money, qty } from "@/lib/format";
import { isQty, toMilli } from "@/lib/money";
import { P, useBranch, useCan, useShop, useShopRouter } from "@/lib/shop-context";

/** What to reorder (PUR-06): at or below the reorder level, with the last 30 days' sales; one click makes the orders. */
export default function ReorderPage() {
  const can = useCan();
  const shop = useShop();
  const router = useShopRouter();
  const [branch, chooseBranch] = useBranch();
  const list = useQuery({ queryKey: ["reorder", branch?.id], queryFn: () => api<Schemas["Suggestion"][]>(`/reorder?branchId=${branch?.id}`), enabled: Boolean(branch) });
  const [qtys, setQtys] = React.useState<Record<number, string>>({});
  const [picked, setPicked] = React.useState<Set<number>>(new Set());
  const rows = list.data ?? [];
  const chosen = rows.filter((r) => picked.has(r.productId) && r.supplierId);
  const make = useMutation({
    mutationFn: () =>
      api<Schemas["PoView"][]>("/reorder/purchase-orders", {
        method: "POST",
        body: { branchId: branch?.id, lines: chosen.map((r) => ({ productId: r.productId, supplierId: r.supplierId, qty: qtys[r.productId] ?? r.suggestedQty })) },
      }),
    onSuccess: (pos) => {
      toast.success(`${pos.length} purchase order${pos.length === 1 ? "" : "s"} made`);
      router.push(pos.length === 1 ? `/purchases/${pos[0].id}` : "/purchases");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader
        title="Reorder"
        description="Products at or below their reorder level. Suggested quantity covers 30 days of sales, less what is on hand and on order."
        actions={
          can(P.PURCHASING_MANAGE) && (
            <Button onClick={() => make.mutate()} loading={make.isPending} disabled={!chosen.length}>
              <ClipboardList /> Make {chosen.length || ""} order lines
            </Button>
          )
        }
      />
      {shop.branches.length > 1 && branch && (
        <Select value={branch.id} onChange={(e) => chooseBranch(Number(e.target.value))} className="mb-3 w-auto" aria-label="Branch">
          {shop.branches.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
            </option>
          ))}
        </Select>
      )}
      <Panel padded={false}>
        {list.isPending ? (
          <TableSkeleton />
        ) : list.isError ? (
          <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />
        ) : rows.length === 0 ? (
          <Empty title="Nothing to reorder" description="No product is at or below its reorder level. Set reorder levels on products." />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th className="w-10">
                  <input
                    type="checkbox"
                    className="accent-[#4fbfae]"
                    aria-label="Select all"
                    checked={rows.filter((r) => r.supplierId).every((r) => picked.has(r.productId))}
                    onChange={(e) => setPicked(e.target.checked ? new Set(rows.filter((r) => r.supplierId).map((r) => r.productId)) : new Set())}
                  />
                </Th>
                <Th>Product</Th>
                <Th>Supplier</Th>
                <Th align="right">On hand</Th>
                <Th align="right">On order</Th>
                <Th align="right">Reorder at</Th>
                <Th align="right">Sold 30 days</Th>
                <Th align="right">Order qty</Th>
                <Th align="right">Cost</Th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const q = qtys[r.productId] ?? r.suggestedQty;
                return (
                  <tr key={r.productId}>
                    <Td>
                      <input
                        type="checkbox"
                        className="accent-[#4fbfae]"
                        disabled={!r.supplierId}
                        aria-label={`Select ${r.name}`}
                        checked={picked.has(r.productId)}
                        onChange={() =>
                          setPicked((s) => {
                            const next = new Set(s);
                            if (next.has(r.productId)) next.delete(r.productId);
                            else next.add(r.productId);
                            return next;
                          })
                        }
                      />
                    </Td>
                    <Td>
                      {r.name}
                      <span className="block text-xs text-faint">{r.sku}</span>
                    </Td>
                    <Td className="text-muted">{r.supplierName ?? <span className="text-warn">No supplier yet</span>}</Td>
                    <Td align="right" className={toMilli(r.onHand) <= BigInt(0) ? "text-bad" : undefined}>
                      {qty(r.onHand)}
                    </Td>
                    <Td align="right">{qty(r.onOrder)}</Td>
                    <Td align="right">{qty(r.reorderLevel)}</Td>
                    <Td align="right">{qty(r.sold30)}</Td>
                    <Td align="right">
                      <Input
                        value={q}
                        onChange={(e) => setQtys((s) => ({ ...s, [r.productId]: e.target.value }))}
                        className={`ml-auto w-20 text-right font-mono ${isQty(q) ? "" : "border-bad"}`}
                        aria-label={`Order quantity of ${r.name}`}
                      />
                    </Td>
                    <Td align="right">{money(r.unitCost, false)}</Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Panel>
    </>
  );
}
