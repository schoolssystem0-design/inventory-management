"use client";

import { Plus } from "lucide-react";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import type { Schemas } from "@/lib/api/client";
import { dateTime } from "@/lib/format";
import { P, useCan, useShop, useShopHref } from "@/lib/shop-context";

type Row = Schemas["TransferListItem"];

export default function TransfersPage() {
  const can = useCan();
  const shop = useShop();
  const toHref = useShopHref();
  const columns: Column<Row>[] = [
    { label: "Transfer", render: (t) => <span className="font-mono font-medium">{t.number}</span> },
    { label: "From", render: (t) => t.fromBranchName },
    { label: "To", render: (t) => t.toBranchName },
    { label: "Sent", render: (t) => <span className="text-muted">{dateTime(t.dispatchedAt)}</span> },
    { label: "Received", render: (t) => <span className="text-muted">{dateTime(t.receivedAt)}</span> },
    { label: "Lines", render: (t) => t.lines, align: "right" },
    { label: "Status", render: (t) => <Status value={t.status} /> },
  ];
  return (
    <ListPage<Row>
      title="Transfers"
      description="Stock sent between branches and godowns. It leaves when sent and arrives when the other branch receives it."
      path="/transfers"
      queryKey="transfers"
      columns={columns}
      actions={
        can(P.STOCK_TRANSFER) &&
        shop.branches.length > 1 && (
          <LinkButton href={toHref("/stock/transfers/new")}>
            <Plus /> New transfer
          </LinkButton>
        )
      }
      filters={[
        ...branchFilter(shop.branches),
        {
          kind: "select",
          param: "status",
          label: "Status",
          options: [
            { value: "", label: "All" },
            { value: "IN_TRANSIT", label: "In transit" },
            { value: "RECEIVED", label: "Received" },
          ],
        },
      ]}
      rowHref={(t) => `/stock/transfers/${t.id}`}
      empty={{ title: shop.branches.length > 1 ? "No transfers yet" : "Transfers need a second branch or godown" }}
    />
  );
}
