"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, label, money, qty } from "@/lib/format";
import { ShopLink, useShopHref } from "@/lib/shop-context";

export default function PurchaseReturnPage() {
  const { id } = useParams<{ id: string }>();
  const toHref = useShopHref();
  const r = useQuery({ queryKey: ["purchase-return", id], queryFn: () => api<Schemas["PurchaseReturnView"]>(`/purchase-returns/${id}`) });
  if (r.isPending) return <Skeleton className="h-96" />;
  if (r.isError) return <ErrorState message={errorMessage(r.error)} onRetry={() => r.refetch()} />;
  const v = r.data;
  return (
    <>
      <PageHeader back={{ href: toHref("/purchases/returns"), label: "Purchase returns" }} title={<span className="font-mono">{v.number}</span>} description={`${v.supplierName} · ${dateTime(v.at)}`} />
      <Panel padded={false} title="Items">
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Qty</Th>
              <Th align="right">Cost</Th>
              <Th align="right">Value</Th>
            </tr>
          </thead>
          <tbody>
            {v.lines.map((l, i) => (
              <tr key={i}>
                <Td>
                  {l.productName}
                  <span className="block text-xs text-faint">{[l.batchNo && `Batch ${l.batchNo}`, l.serial].filter(Boolean).join(" · ")}</span>
                </Td>
                <Td align="right">{qty(l.qty)}</Td>
                <Td align="right">{money(l.unitCost, false)}</Td>
                <Td align="right">{money(l.value, false)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <p className="border-t border-line px-4 py-3 text-right text-sm">
          Total <span className="font-mono text-lg font-semibold">{money(v.total)}</span>
        </p>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Reason", value: label(v.reason) },
            { label: "Bill", value: v.grnId ? <ShopLink href={`/purchases/grns/${v.grnId}`} className="text-aqua">{v.grnNumber}</ShopLink> : null },
            { label: "By", value: v.actorName },
            { label: "Note", value: v.note },
          ]}
        />
      </Panel>
    </>
  );
}
