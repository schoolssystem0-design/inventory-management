"use client";

import { Plus } from "lucide-react";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { LinkButton } from "@/components/ui/button";
import type { Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

type Row = Schemas["QuotationView"];

export default function QuotationsPage() {
  const can = useCan();
  const toHref = useShopHref();
  const columns: Column<Row>[] = [
    { label: "Quotation", render: (q) => <span className="font-mono font-medium">{q.number}</span> },
    { label: "Date", render: (q) => <span className="text-muted">{date(q.date)}</span> },
    { label: "Customer", render: (q) => q.customerName ?? "—" },
    { label: "Valid until", render: (q) => <span className={q.expired ? "text-bad" : "text-muted"}>{date(q.validUntil)}</span> },
    { label: "Total", render: (q) => money(q.total, false), align: "right" },
    { label: "Status", render: (q) => <Status value={q.expired && q.status === "OPEN" ? "EXPIRED" : q.status} /> },
  ];
  return (
    <ListPage<Row>
      title="Quotations"
      description="Price quotes for customers. Convert one into a sale when they buy."
      path="/quotations"
      queryKey="quotations"
      columns={columns}
      actions={
        can(P.QUOTATIONS_MANAGE) && (
          <LinkButton href={toHref("/quotations/new")}>
            <Plus /> New quotation
          </LinkButton>
        )
      }
      filters={[
        {
          kind: "select",
          param: "status",
          label: "Status",
          options: [
            { value: "", label: "All" },
            { value: "OPEN", label: "Open" },
            { value: "CONVERTED", label: "Converted" },
            { value: "CANCELLED", label: "Cancelled" },
          ],
        },
      ]}
      rowHref={(q) => `/quotations/${q.id}`}
      empty={{ title: "No quotations yet" }}
    />
  );
}
