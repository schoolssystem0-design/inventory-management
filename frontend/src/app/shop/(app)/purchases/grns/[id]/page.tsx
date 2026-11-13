"use client";

import { useQuery } from "@tanstack/react-query";
import { HandCoins, RotateCcw } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { Button, LinkButton } from "@/components/ui/button";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { PaymentEntryDialog } from "@/features/parties/party-form";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, money, qty } from "@/lib/format";
import { P, ShopLink, useCan, useShopHref } from "@/lib/shop-context";

export default function GrnPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const [paying, setPaying] = React.useState(false);
  const grn = useQuery({ queryKey: ["grn", id], queryFn: () => api<Schemas["GrnView"]>(`/grns/${id}`) });
  if (grn.isPending) return <Skeleton className="h-96" />;
  if (grn.isError) return <ErrorState message={errorMessage(grn.error)} onRetry={() => grn.refetch()} />;
  const g = grn.data;
  return (
    <>
      <PageHeader
        back={{ href: toHref("/purchases"), label: "Purchasing" }}
        title={<span className="font-mono">{g.number}</span>}
        description={`${g.supplierName} · ${g.branchName} · ${dateTime(g.at)}`}
        actions={
          <>
            {g.due !== "0.00" && can(P.SUPPLIERS_PAYMENTS) && (
              <Button onClick={() => setPaying(true)}>
                <HandCoins /> Pay {money(g.due)}
              </Button>
            )}
            {can(P.PURCHASING_MANAGE) && (
              <LinkButton variant="secondary" href={toHref(`/purchases/returns/new?grn=${g.id}`)}>
                <RotateCcw /> Return to supplier
              </LinkButton>
            )}
          </>
        }
      />
      <Panel padded={false} title="Items">
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Qty</Th>
              <Th align="right">Bonus</Th>
              <Th align="right">Cost</Th>
              <Th align="right">Discount</Th>
              <Th align="right">Net</Th>
              <Th align="right">Cost per base unit</Th>
              <Th align="right">Returned</Th>
            </tr>
          </thead>
          <tbody>
            {g.lines.map((l) => (
              <tr key={l.id}>
                <Td>
                  {l.productName}
                  <span className="block text-xs text-faint">
                    {[l.batchNo && `Batch ${l.batchNo} · exp ${date(l.expiry)}`, l.serials?.length ? `${l.serials.length} IMEI: ${l.serials.slice(0, 3).join(", ")}${l.serials.length > 3 ? "…" : ""}` : null]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </Td>
                <Td align="right">
                  {qty(l.qty)} {l.unitName}
                </Td>
                <Td align="right">{l.bonusQty && l.bonusQty !== "0" && l.bonusQty !== "0.000" ? qty(l.bonusQty) : "—"}</Td>
                <Td align="right">{money(l.unitCost, false)}</Td>
                <Td align="right">{l.discount !== "0.00" ? money(l.discount, false) : "—"}</Td>
                <Td align="right">{money(l.net, false)}</Td>
                <Td align="right" className="text-muted">
                  {money(l.baseUnitCost, false)}
                </Td>
                <Td align="right">{l.qtyReturned !== "0" && l.qtyReturned !== "0.000" ? qty(l.qtyReturned) : "—"}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <dl className="grid grid-cols-2 gap-2 border-t border-line p-4 text-sm sm:ml-auto sm:max-w-xs">
          <dt className="text-muted">Items</dt>
          <dd className="text-right font-mono">{money(g.subtotal)}</dd>
          {g.freight !== "0.00" && (
            <>
              <dt className="text-muted">Freight</dt>
              <dd className="text-right font-mono">{money(g.freight)}</dd>
            </>
          )}
          {g.tax !== "0.00" && (
            <>
              <dt className="text-muted">Tax</dt>
              <dd className="text-right font-mono">{money(g.tax)}</dd>
            </>
          )}
          {g.discount !== "0.00" && (
            <>
              <dt className="text-muted">Discount</dt>
              <dd className="text-right font-mono">−{money(g.discount)}</dd>
            </>
          )}
          <dt className="font-semibold">Total</dt>
          <dd className="text-right font-mono text-lg font-semibold">{money(g.total)}</dd>
          <dt className="text-muted">Paid</dt>
          <dd className="text-right font-mono">{money(g.paid)}</dd>
          {g.returned !== "0.00" && (
            <>
              <dt className="text-muted">Returned</dt>
              <dd className="text-right font-mono">{money(g.returned)}</dd>
            </>
          )}
          <dt className="font-semibold text-warn">Due</dt>
          <dd className="text-right font-mono font-semibold text-warn">{money(g.due)}</dd>
        </dl>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Supplier's bill", value: g.supplierBillNo ? `${g.supplierBillNo} · ${date(g.billDate)}` : null },
            { label: "Due by", value: date(g.dueOn) },
            { label: "Order", value: g.poId ? <ShopLink href={`/purchases/${g.poId}`} className="text-aqua">{g.poNumber}</ShopLink> : "Without an order" },
            { label: "Received by", value: g.actorName },
            { label: "Note", value: g.note },
          ]}
        />
      </Panel>
      {paying && <PaymentEntryDialog kind="supplier" partyId={g.supplierId} partyName={g.supplierName} balance={g.due} branchId={g.branchId} onClose={() => setPaying(false)} />}
    </>
  );
}
