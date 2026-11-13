"use client";

import { useQuery } from "@tanstack/react-query";
import { ScanLine } from "lucide-react";
import * as React from "react";
import { ScanButton } from "@/components/barcode-scanner";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Dialog } from "@/components/ui/dialog";
import { Badge, DetailList, ErrorState, Skeleton } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, label, money } from "@/lib/format";
import { P, ShopLink, useCan } from "@/lib/shop-context";
import { useQueryParam } from "@/lib/use-browser-value";

type Row = Schemas["SerialListItem"];

/** IMEI search (MOB-01): every phone by IMEI, with where it came from, who bought it and its warranty. */
export default function ImeiPage() {
  const can = useCan();
  const productId = useQueryParam("productId");
  const [open, setOpen] = React.useState<string | null>(null);
  const columns: Column<Row>[] = [
    {
      label: "IMEI",
      render: (s) => (
        <button className="text-left font-mono text-ink hover:text-aqua" onClick={() => setOpen(s.serial)}>
          {s.serial}
          {s.serial2 && <span className="block text-xs text-faint">{s.serial2}</span>}
        </button>
      ),
    },
    { label: "Model", render: (s) => s.productName },
    { label: "Details", render: (s) => <span className="text-muted">{[s.storage, s.colour, label(s.condition), s.ptaStatus && label(s.ptaStatus)].filter(Boolean).join(" · ")}</span> },
    { label: "Branch", render: (s) => <span className="text-muted">{s.branchName}</span> },
    { label: "Cost", render: (s) => money(s.cost, false), align: "right", hidden: !can(P.COST_VIEW) },
    { label: "Status", render: (s) => <Status value={s.status} /> },
  ];
  return (
    <>
      <ListPage<Row>
        title="IMEI search"
        description="Scan or type an IMEI to see its whole story."
        path="/serials"
        queryKey="serials"
        columns={columns}
        extraParams={{ productId }}
        filters={[
          { kind: "search", placeholder: "IMEI, serial or model" },
          {
            kind: "select",
            param: "status",
            label: "Status",
            initial: "ALL",
            options: [
              { value: "ALL", label: "Any status" },
              { value: "IN_STOCK", label: "In stock" },
              { value: "SOLD", label: "Sold" },
              { value: "IN_TRANSIT", label: "In transit" },
              { value: "DEFECTIVE", label: "Defective" },
              { value: "RETURNED_TO_SUPPLIER", label: "Returned to supplier" },
              { value: "WRITTEN_OFF", label: "Written off" },
            ],
          },
        ]}
        actions={<ScanButton label="Scan IMEI" onCode={(code) => setOpen(code)} />}
        empty={{ title: "No IMEIs yet", description: "Phones get IMEIs when you receive them from a supplier or buy them used." }}
      />
      {open && <SerialDialog serial={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function SerialDialog({ serial, onClose }: { serial: string; onClose: () => void }) {
  const can = useCan();
  const s = useQuery({ queryKey: ["serial", serial], queryFn: () => api<Schemas["SerialView"]>(`/serials/${encodeURIComponent(serial)}`) });
  return (
    <Dialog open wide onOpenChange={(o) => !o && onClose()} title={`IMEI ${serial}`}>
      {s.isPending && <Skeleton className="h-48" />}
      {s.isError && <ErrorState message={errorMessage(s.error)} />}
      {s.data && (
        <div className="space-y-5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{s.data.productName}</span>
            <Status value={s.data.status} />
            {s.data.underWarranty && <Badge tone="good">Under warranty till {date(s.data.warrantyUntil)}</Badge>}
          </div>
          <DetailList
            items={[
              { label: "Second IMEI", value: s.data.serial2 },
              { label: "Storage / colour", value: [s.data.storage, s.data.colour].filter(Boolean).join(" · ") || null },
              { label: "Condition", value: label(s.data.condition) },
              { label: "PTA", value: s.data.ptaStatus ? label(s.data.ptaStatus) : null },
              { label: "Came from", value: s.data.supplierName ? `${s.data.supplierName} (${s.data.purchaseNumber ?? ""})` : label(s.data.source) },
              { label: "Branch", value: s.data.branchName },
              { label: "Cost", value: can(P.COST_VIEW) ? money(s.data.cost) : null },
              {
                label: "Sold",
                value: s.data.saleId ? (
                  <ShopLink href={`/sales/${s.data.saleId}`} className="text-aqua">
                    {s.data.saleNumber} · {dateTime(s.data.soldAt)} · {s.data.customerName ?? "walk-in"}
                  </ShopLink>
                ) : null,
              },
            ]}
          />
          <div>
            <p className="mb-2 flex items-center gap-2 text-sm font-semibold">
              <ScanLine className="size-4 text-aqua" /> History
            </p>
            <ol className="space-y-2 border-l border-line pl-4">
              {s.data.history.map((h, i) => (
                <li key={i} className="text-sm">
                  <span className="font-medium">{h.description}</span>
                  <span className="block text-xs text-faint">
                    {dateTime(h.at)} · {h.actorName}
                    {h.branchName ? ` · ${h.branchName}` : ""}
                    {h.docNumber ? ` · ${h.docNumber}` : ""}
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
      )}
    </Dialog>
  );
}
