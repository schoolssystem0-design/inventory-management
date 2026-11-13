"use client";

import { useQuery } from "@tanstack/react-query";
import { Printer } from "lucide-react";
import * as React from "react";
import { todayIso } from "@/components/list-page";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { docHtml } from "@/features/documents/print-doc";
import { useShopHeader } from "@/features/documents/till";
import { printHtml } from "@/features/receipts/receipt";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date } from "@/lib/format";
import { ShopLink, useShopHref } from "@/lib/shop-context";

type Entry = Schemas["RegisterEntry"];

/** The controlled-medicine register (MED-09): every controlled medicine sold or returned, never changed. */
export default function ControlledRegisterPage() {
  const toHref = useShopHref();
  const header = useShopHeader();
  const [from, setFrom] = React.useState(() => todayIso().slice(0, 8) + "01");
  const [to, setTo] = React.useState(todayIso());
  const list = useQuery({ queryKey: ["controlled-register", from, to], queryFn: () => api<Entry[]>(`/controlled-register?from=${from}&to=${to}`) });
  const print = () =>
    list.data &&
    void printHtml(
      docHtml({
        shopName: header.name,
        shopLines: header.lines,
        title: "Controlled medicines register",
        date: `${date(from)} – ${date(to)}`,
        sections: [
          {
            kind: "table",
            head: ["Date", "Invoice", "Medicine", "Batch", "Qty", "Patient", "Doctor", "Rx", "Pharmacist"],
            right: [4],
            rows: list.data.map((e) => [date(e.date), e.saleNumber, e.productName, e.batchNo ?? "", `${e.qty} ${e.unitName ?? ""}`, e.patientName, e.doctorName, e.prescriptionNumber ?? "", e.pharmacistName ?? ""]),
          },
          { kind: "signatures", labels: ["Pharmacist in charge", "Owner"] },
        ],
      }),
    );
  return (
    <>
      <PageHeader
        back={{ href: toHref("/prescriptions"), label: "Prescriptions" }}
        title="Controlled medicines register"
        description="Written automatically at each sale and return of a controlled medicine. Negative quantities are returns."
        actions={
          <Button variant="secondary" onClick={print} disabled={!list.data?.length}>
            <Printer /> Print
          </Button>
        }
      />
      <div className="mb-4 flex flex-wrap gap-3">
        <Field label="From" className="w-44">
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="To" className="w-44">
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
      </div>
      <Panel padded={false}>
        {list.isPending ? (
          <TableSkeleton />
        ) : list.isError ? (
          <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <Empty title="Nothing in these dates" />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Invoice</Th>
                <Th>Medicine</Th>
                <Th>Batch</Th>
                <Th align="right">Qty</Th>
                <Th>Patient</Th>
                <Th>Doctor</Th>
                <Th>Pharmacist</Th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((e) => (
                <tr key={e.id}>
                  <Td>{date(e.date)}</Td>
                  <Td>
                    <ShopLink href={`/sales/${e.saleId}`} className="font-mono hover:text-aqua">
                      {e.saleNumber}
                    </ShopLink>
                  </Td>
                  <Td>{e.productName}</Td>
                  <Td className="font-mono text-muted">{e.batchNo ?? "—"}</Td>
                  <Td align="right" className={e.qty.startsWith("-") ? "text-warn" : ""}>
                    {e.qty} {e.unitName}
                  </Td>
                  <Td>{e.patientName}</Td>
                  <Td>{e.doctorName}</Td>
                  <Td>{e.pharmacistName ?? "—"}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
    </>
  );
}
