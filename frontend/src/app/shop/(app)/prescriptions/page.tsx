"use client";

import { BookLock } from "lucide-react";
import { type Column, ListPage } from "@/components/list-page";
import { LinkButton } from "@/components/ui/button";
import { fileUrl, type Schemas } from "@/lib/api/client";
import { date } from "@/lib/format";
import { P, ShopLink, useCan, useShopHref } from "@/lib/shop-context";

type Rx = Schemas["PrescriptionView"];

/** Prescriptions taken with sales (MED-08). Controlled medicines are also in the register. */
export default function PrescriptionsPage() {
  const can = useCan();
  const toHref = useShopHref();
  const columns: Column<Rx>[] = [
    { label: "No.", render: (r) => <span className="font-mono font-medium">{r.number}</span> },
    { label: "Date", render: (r) => <span className="text-muted">{date(r.date)}</span> },
    {
      label: "Patient",
      render: (r) => (
        <>
          {r.patientName}
          {r.patientAge && <span className="text-muted"> · {r.patientAge}</span>}
          {r.patientPhone && <span className="block text-xs text-faint">{r.patientPhone}</span>}
        </>
      ),
    },
    {
      label: "Doctor",
      render: (r) => (
        <>
          {r.doctorName}
          {r.doctorRegNo && <span className="block text-xs text-faint">{r.doctorRegNo}</span>}
        </>
      ),
    },
    { label: "Sale", render: (r) => (r.saleId ? <ShopLink href={`/sales/${r.saleId}`} className="font-mono hover:text-aqua">{r.saleNumber}</ShopLink> : "—") },
    {
      label: "Photo",
      render: (r) =>
        r.fileId ? (
          <a href={fileUrl(r.fileId) ?? undefined} target="_blank" rel="noreferrer" className="text-aqua">
            View
          </a>
        ) : (
          "—"
        ),
    },
  ];
  return (
    <ListPage<Rx>
      title="Prescriptions"
      description="Taken at the till for prescription (Rx) and controlled medicines."
      path="/prescriptions"
      queryKey="prescriptions"
      columns={columns}
      actions={
        (can(P.PRESCRIPTIONS_APPROVE) || can(P.REPORTS_VIEW)) && (
          <LinkButton variant="secondary" href={toHref("/prescriptions/register")}>
            <BookLock /> Controlled register
          </LinkButton>
        )
      }
      filters={[{ kind: "search", placeholder: "Patient, doctor or number" }, { kind: "dates" }]}
      empty={{ title: "No prescriptions yet" }}
    />
  );
}
