"use client";

import { branchFilter, type Column, daysAgoIso, ListPage, todayIso } from "@/components/list-page";
import type { Schemas } from "@/lib/api/client";
import { dateTime, label, money, qty } from "@/lib/format";
import { P, useCan, useShop } from "@/lib/shop-context";
import { useQueryParam } from "@/lib/use-browser-value";

type Row = Schemas["MovementRow"];

const TYPES = ["OPENING", "PURCHASE", "SALE", "SALE_RETURN", "CANCEL", "PURCHASE_RETURN", "ADJUST_IN", "ADJUST_OUT", "COUNT", "TRANSFER_OUT", "TRANSFER_IN", "BUYBACK"];

export default function MovementsPage() {
  const shop = useShop();
  const can = useCan();
  const productId = useQueryParam("productId");
  const columns: Column<Row>[] = [
    { label: "When", render: (m) => <span className="text-muted">{dateTime(m.at)}</span> },
    {
      label: "Product",
      render: (m) => (
        <>
          {m.productName}
          {(m.batchNo || m.serial) && <span className="block text-xs text-faint">{[m.batchNo && `Batch ${m.batchNo}`, m.serial].filter(Boolean).join(" · ")}</span>}
        </>
      ),
    },
    { label: "Type", render: (m) => label(m.type) },
    { label: "Document", render: (m) => <span className="font-mono text-xs text-muted">{m.docNumber ?? "—"}</span> },
    { label: "Branch", render: (m) => <span className="text-muted">{m.branchName}</span>, hidden: shop.branches.length < 2 },
    { label: "Qty", align: "right", render: (m) => <span className={m.qty.startsWith("-") ? "text-warn" : "text-good"}>{m.qty.startsWith("-") ? "" : "+"}{qty(m.qty)}</span> },
    { label: "Balance", align: "right", render: (m) => qty(m.balanceAfter) },
    { label: "Cost", align: "right", render: (m) => money(m.unitCost, false), hidden: !can(P.COST_VIEW) },
    { label: "By", render: (m) => <span className="text-muted">{m.actorName}</span> },
  ];
  return (
    <ListPage<Row>
      title="Stock movements"
      description="Every change to stock, with the document that made it. Nothing is ever edited or deleted."
      path="/stock/movements"
      queryKey="movements"
      columns={columns}
      extraParams={{ productId }}
      filters={[
        { kind: "dates", from: daysAgoIso(30), to: todayIso() },
        ...branchFilter(shop.branches),
        { kind: "select", param: "type", label: "Type", options: [{ value: "", label: "All types" }, ...TYPES.map((t) => ({ value: t, label: label(t) }))] },
      ]}
      empty={{ title: "No movements in these dates" }}
    />
  );
}
