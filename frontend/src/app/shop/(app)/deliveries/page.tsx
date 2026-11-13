"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { DetailList } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime } from "@/lib/format";
import { P, ShopLink, useCan } from "@/lib/shop-context";

type Delivery = Schemas["DeliveryView"];
const STATUSES = ["PENDING", "ON_THE_WAY", "DELIVERED", "INSTALLED", "FAILED", "CANCELLED"];
const CLOSED = ["DELIVERED", "INSTALLED", "CANCELLED"];

/** Deliveries (ELE-04): goods of an invoice or challan taken to the customer, with installation when needed. */
export default function DeliveriesPage() {
  const [open, setOpen] = React.useState<Delivery | null>(null);
  const columns: Column<Delivery>[] = [
    {
      label: "Delivery",
      render: (d) => (
        <button className="text-left font-mono font-medium hover:text-aqua" onClick={() => setOpen(d)}>
          {d.number}
        </button>
      ),
    },
    { label: "For", render: (d) => <span className="font-mono text-muted">{d.saleNumber ?? d.challanNumber}</span> },
    {
      label: "Customer",
      render: (d) => (
        <>
          {d.customerName}
          <span className="block text-xs text-faint">{d.address}</span>
        </>
      ),
    },
    { label: "Date", render: (d) => (d.scheduledOn ? date(d.scheduledOn) : "—") },
    { label: "Driver", render: (d) => [d.driver, d.vehicle].filter(Boolean).join(" · ") || "—" },
    { label: "Installation", render: (d) => (d.installation ? "Yes" : "—") },
    { label: "Status", render: (d) => <Status value={d.status} /> },
  ];
  return (
    <>
      <ListPage<Delivery>
        title="Deliveries"
        description="Add a delivery from an invoice or a challan. The customer gets an SMS when it is on the way."
        path="/deliveries"
        queryKey="deliveries"
        columns={columns}
        filters={[
          {
            kind: "select",
            param: "status",
            label: "Status",
            initial: "OPEN",
            options: [{ value: "OPEN", label: "Pending and on the way" }, { value: "", label: "All" }, ...STATUSES.map((s) => ({ value: s, label: s === "ON_THE_WAY" ? "On the way" : s.charAt(0) + s.slice(1).toLowerCase() }))],
          },
          { kind: "dates" },
        ]}
        empty={{ title: "No deliveries", description: "Open an invoice or challan and choose Arrange delivery." }}
      />
      {open && <DeliveryDialog delivery={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function DeliveryDialog({ delivery: d, onClose }: { delivery: Delivery; onClose: () => void }) {
  const can = useCan();
  const client = useQueryClient();
  const editable = can(P.DELIVERY_MANAGE) && !CLOSED.includes(d.status);
  const [form, setForm] = React.useState({ status: d.status, driver: d.driver ?? "", vehicle: d.vehicle ?? "", scheduledOn: d.scheduledOn ?? "", receivedBy: d.receivedBy ?? "", note: d.note ?? "" });
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () =>
      api<Delivery>(`/deliveries/${d.id}`, {
        method: "PATCH",
        body: { status: form.status, driver: form.driver || null, vehicle: form.vehicle || null, scheduledOn: form.scheduledOn || null, receivedBy: form.receivedBy || null, note: form.note || null },
      }),
    onSuccess: (v) => {
      toast.success(`${v.number}: ${v.status === "ON_THE_WAY" ? "on the way" : v.status.toLowerCase()}`);
      client.invalidateQueries({ queryKey: ["deliveries"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Delivery ${d.number}`}
      footer={
        editable && (
          <Button onClick={() => save.mutate()} loading={save.isPending}>
            Save
          </Button>
        )
      }
    >
      <div className="space-y-4">
        <DetailList
          items={[
            { label: "For", value: d.saleId ? <ShopLink href={`/sales/${d.saleId}`} className="font-mono text-aqua">{d.saleNumber}</ShopLink> : <ShopLink href={`/challans/${d.challanId}`} className="font-mono text-aqua">{d.challanNumber}</ShopLink> },
            { label: "Customer", value: [d.customerName, d.customerPhone].filter(Boolean).join(" · ") },
            { label: "Address", value: d.address },
            { label: "Installation", value: d.installation ? "Needed" : "No" },
            { label: "Made", value: `${dateTime(d.at)} by ${d.actorName}` },
            { label: "Done", value: d.doneAt ? dateTime(d.doneAt) : null },
          ]}
        />
        {editable && (
          <>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Status">
                <Select value={form.status} onChange={(e) => set("status", e.target.value)}>
                  {STATUSES.filter((s) => s !== "INSTALLED" || d.installation).map((s) => (
                    <option key={s} value={s}>
                      {s === "ON_THE_WAY" ? "On the way" : s.charAt(0) + s.slice(1).toLowerCase()}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="Date">
                <Input type="date" value={form.scheduledOn} onChange={(e) => set("scheduledOn", e.target.value)} />
              </Field>
              <Field label="Driver">
                <Input value={form.driver} onChange={(e) => set("driver", e.target.value)} />
              </Field>
              <Field label="Vehicle">
                <Input value={form.vehicle} onChange={(e) => set("vehicle", e.target.value)} />
              </Field>
            </div>
            <Field label="Received by">
              <Input value={form.receivedBy} onChange={(e) => set("receivedBy", e.target.value)} />
            </Field>
            <Field label="Note">
              <Textarea value={form.note} onChange={(e) => set("note", e.target.value)} rows={2} />
            </Field>
          </>
        )}
        {save.error && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </div>
    </Dialog>
  );
}
