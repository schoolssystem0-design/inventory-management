"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, label, money, qty } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

export default function AdjustmentPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const a = useQuery({ queryKey: ["adjustment", id], queryFn: () => api<Schemas["AdjustmentView"]>(`/adjustments/${id}`) });
  if (a.isPending) return <Skeleton className="h-96" />;
  if (a.isError) return <ErrorState message={errorMessage(a.error)} onRetry={() => a.refetch()} />;
  const v = a.data;
  const cost = can(P.COST_VIEW);
  return (
    <>
      <PageHeader back={{ href: toHref("/stock/adjustments"), label: "Adjustments" }} title={<span className="font-mono">{v.number}</span>} description={`${label(v.reason)} · ${v.branchName} · ${dateTime(v.at)}`} />
      <Panel padded={false} title="Items">
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Qty</Th>
              {cost && <Th align="right">Cost</Th>}
              {cost && <Th align="right">Value</Th>}
            </tr>
          </thead>
          <tbody>
            {v.lines.map((l, i) => (
              <tr key={i}>
                <Td>
                  {l.productName}
                  <span className="block text-xs text-faint">{[l.batchNo && `Batch ${l.batchNo}`, l.serial].filter(Boolean).join(" · ")}</span>
                </Td>
                <Td align="right" className={l.qty.startsWith("-") ? "text-warn" : "text-good"}>
                  {qty(l.qty)}
                </Td>
                {cost && <Td align="right">{money(l.unitCost, false)}</Td>}
                {cost && <Td align="right">{money(l.value, false)}</Td>}
              </tr>
            ))}
          </tbody>
        </Table>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Value out", value: cost ? money(v.valueOut) : "—" },
            { label: "Value in", value: cost ? money(v.valueIn) : "—" },
            { label: "By", value: v.actorName },
            { label: "Approved by", value: v.approvedByName },
            { label: "Business date", value: v.businessDate },
            { label: "Note", value: v.note },
          ]}
        />
      </Panel>
    </>
  );
}
