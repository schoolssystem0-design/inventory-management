"use client";

import { useQuery } from "@tanstack/react-query";
import { ClipboardCheck, History, PackageOpen, SlidersHorizontal } from "lucide-react";
import * as React from "react";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { date, money, qty } from "@/lib/format";
import { P, ShopLink, useCan, useShop, useShopHref } from "@/lib/shop-context";

type Row = Schemas["StockRow"];

export default function StockPage() {
  const can = useCan();
  const shop = useShop();
  const toHref = useShopHref();
  const [open, setOpen] = React.useState<Row | null>(null);
  const cost = can(P.COST_VIEW);
  const columns: Column<Row>[] = [
    {
      label: "Product",
      render: (r) => (
        <button className="text-left" onClick={() => setOpen(r)}>
          <span className="font-medium text-ink hover:text-aqua">{r.name}</span>
          <span className="block text-xs text-faint">
            {r.sku}
            {r.categoryName ? ` · ${r.categoryName}` : ""}
          </span>
        </button>
      ),
    },
    { label: "On hand", align: "right", render: (r) => <span className={r.qty.startsWith("-") ? "text-bad" : undefined}>{qty(r.qty)} {r.unitName}</span> },
    { label: "Reorder at", align: "right", render: (r) => (r.reorderLevel ? qty(r.reorderLevel) : "—") },
    { label: "Avg cost", align: "right", render: (r) => money(r.costAvg, false), hidden: !cost },
    { label: "Cost value", align: "right", render: (r) => money(r.costValue, false), hidden: !cost },
    { label: "Retail value", align: "right", render: (r) => money(r.retailValue, false) },
    { label: "Status", render: (r) => (r.status && r.status !== "OK" ? <Status value={r.status} /> : null) },
  ];
  return (
    <>
      <ListPage<Row>
        title="Stock"
        description="What you have at each branch, and what it is worth."
        path="/stock"
        queryKey="stock"
        columns={columns}
        actions={
          <>
            {can(P.STOCK_ADJUST) && (
              <>
                <LinkButton variant="secondary" href={toHref("/stock/opening")}>
                  <PackageOpen /> Opening stock
                </LinkButton>
                <LinkButton variant="secondary" href={toHref("/stock/adjustments/new")}>
                  <SlidersHorizontal /> Adjust
                </LinkButton>
              </>
            )}
            {can(P.STOCK_COUNT) && (
              <LinkButton variant="secondary" href={toHref("/stock/counts")}>
                <ClipboardCheck /> Count
              </LinkButton>
            )}
            <LinkButton variant="ghost" href={toHref("/stock/movements")}>
              <History /> Movements
            </LinkButton>
          </>
        }
        filters={[
          { kind: "search", placeholder: "Name, SKU or barcode" },
          ...branchFilter(shop.branches),
          {
            kind: "select",
            param: "filter",
            label: "Show",
            options: [
              { value: "", label: "All products" },
              { value: "low", label: "Low (at or below reorder)" },
              { value: "out", label: "Out of stock" },
              { value: "negative", label: "Below zero" },
            ],
          },
        ]}
        summary={(d) => (
          <span>
            {d.total.toLocaleString()} products · {qty(d.totalQty as string)} units
            {cost && (
              <>
                {" "}
                · cost <span className="font-mono text-ink">{money(d.totalCostValue as string)}</span>
              </>
            )}{" "}
            · retail <span className="font-mono text-ink">{money(d.totalRetailValue as string)}</span>
          </span>
        )}
        empty={{ title: "No stock yet", description: "Receive goods from a supplier or enter opening stock." }}
      />
      {open && <ProductStock row={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function ProductStock({ row, onClose }: { row: Row; onClose: () => void }) {
  const toHref = useShopHref();
  const s = useQuery({ queryKey: ["product-stock", row.productId], queryFn: () => api<Schemas["ProductStockView"]>(`/stock/products/${row.productId}`) });
  return (
    <Dialog open wide onOpenChange={(o) => !o && onClose()} title={row.name} description={row.sku}>
      {!s.data ? (
        <Skeleton className="h-40" />
      ) : (
        <div className="space-y-4">
          <Table className="min-w-0">
            <thead>
              <tr>
                <Th>Branch</Th>
                <Th align="right">On hand</Th>
                <Th align="right">On the way</Th>
              </tr>
            </thead>
            <tbody>
              {s.data.branches.map((b) => (
                <tr key={b.branchId}>
                  <Td>{b.branchName}</Td>
                  <Td align="right">{qty(b.qty)}</Td>
                  <Td align="right">{b.inTransit && b.inTransit !== "0" && b.inTransit !== "0.000" ? qty(b.inTransit) : "—"}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {s.data.batches.length > 0 && (
            <Table className="min-w-0">
              <thead>
                <tr>
                  <Th>Batch</Th>
                  <Th>Expiry</Th>
                  <Th>Branch</Th>
                  <Th align="right">Qty</Th>
                </tr>
              </thead>
              <tbody>
                {s.data.batches.map((b) => (
                  <tr key={`${b.batchId}-${b.branchId}`}>
                    <Td className="font-mono">{b.batchNo}</Td>
                    <Td className={b.expired ? "text-bad" : undefined}>{date(b.expiry)}</Td>
                    <Td>{b.branchName}</Td>
                    <Td align="right">{qty(b.qty)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
          {s.data.kind === "SERIAL" && (
            <p className="text-sm text-muted">
              {s.data.serialsInStock} IMEI in stock ·{" "}
              <ShopLink href={`/imei?productId=${row.productId}`} className="text-aqua">
                see them
              </ShopLink>
            </p>
          )}
          <p className="flex gap-4 text-sm">
            <ShopLink href={`/stock/movements?productId=${row.productId}`} className="text-aqua">
              Movement history
            </ShopLink>
            <a href={toHref(`/products/${row.productId}`)} className="text-aqua">
              Product
            </a>
          </p>
        </div>
      )}
    </Dialog>
  );
}
