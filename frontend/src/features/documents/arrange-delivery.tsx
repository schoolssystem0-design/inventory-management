"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Checkbox, Field, fieldError, Input, Textarea } from "@/components/ui/fields";
import { Dialog } from "@/components/ui/dialog";
import { todayIso } from "@/components/list-page";
import { api, errorMessage, type Schemas } from "@/lib/api/client";

/**
 * Sets up a delivery for an invoice or a challan (ELE-04). Name, phone and address default to the document's
 * customer; a delivery charge, if any, is billed as a line on the invoice.
 */
export function ArrangeDelivery({
  saleId,
  challanId,
  customerName,
  address,
  onClose,
}: {
  saleId?: number;
  challanId?: number;
  customerName?: string | null;
  address?: string | null;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const [form, setForm] = React.useState({ customerName: customerName ?? "", customerPhone: "", address: address ?? "", scheduledOn: todayIso(), driver: "", vehicle: "", installation: false, note: "" });
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["DeliveryView"]>("/deliveries", {
        method: "POST",
        body: {
          saleId: saleId ?? null,
          challanId: challanId ?? null,
          customerName: form.customerName || null,
          customerPhone: form.customerPhone || null,
          address: form.address || null,
          scheduledOn: form.scheduledOn || null,
          driver: form.driver || null,
          vehicle: form.vehicle || null,
          installation: form.installation,
          note: form.note || null,
        },
      }),
    onSuccess: (d) => {
      toast.success(`Delivery ${d.number} added`);
      client.invalidateQueries({ queryKey: ["deliveries"] });
      onClose();
    },
  });
  const err = (n: string) => fieldError(save.error, n);
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Arrange delivery"
      footer={
        <Button type="submit" form="delivery-form" loading={save.isPending}>
          Add delivery
        </Button>
      }
    >
      <form
        id="delivery-form"
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid grid-cols-2 gap-3">
          <Field label="Receiver" error={err("customerName")}>
            <Input value={form.customerName} onChange={(e) => set("customerName", e.target.value)} />
          </Field>
          <Field label="Phone" hint="Empty = the customer's phone">
            <Input value={form.customerPhone} onChange={(e) => set("customerPhone", e.target.value)} inputMode="tel" />
          </Field>
        </div>
        <Field label="Address" required error={err("address")}>
          <Textarea value={form.address} onChange={(e) => set("address", e.target.value)} rows={2} autoFocus />
        </Field>
        <div className="grid grid-cols-3 gap-3">
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
        <Checkbox label="Needs installation" checked={form.installation} onChange={(e) => set("installation", e.target.checked)} />
        <Field label="Note">
          <Input value={form.note} onChange={(e) => set("note", e.target.value)} />
        </Field>
        {save.error && !err("address") && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}
