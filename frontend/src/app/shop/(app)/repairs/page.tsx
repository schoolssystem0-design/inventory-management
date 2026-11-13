"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, fieldError, Input, Select, Textarea } from "@/components/ui/fields";
import { StatCard } from "@/components/ui/layout";
import { useTillSession } from "@/features/documents/till";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, money } from "@/lib/format";
import { P, useCan, useShopRouter } from "@/lib/shop-context";

type Job = Schemas["RepairView"];
const REPAIR_STATUSES = ["RECEIVED", "CHECKING", "WAITING_PARTS", "REPAIRING", "READY", "NOT_REPAIRABLE", "DELIVERED", "CANCELLED"];

/** Repair job cards (MOB-07): book a device in, track it, hand it back with an invoice. */
export default function RepairsPage() {
  const can = useCan();
  const [booking, setBooking] = React.useState(false);
  const counts = useQuery({ queryKey: ["repair-counts"], queryFn: () => api<Schemas["RepairCounts"]>("/repairs/counts") });
  const columns: Column<Job>[] = [
    { label: "Job", render: (j) => <span className="font-mono font-medium">{j.number}</span> },
    { label: "Booked", render: (j) => <span className="text-muted">{date(j.date)}</span> },
    {
      label: "Device",
      render: (j) => (
        <>
          {j.device}
          <span className="block text-xs text-faint">{j.problem}</span>
        </>
      ),
    },
    {
      label: "Customer",
      render: (j) => (
        <>
          {j.customerName}
          <span className="block text-xs text-faint">{j.customerPhone}</span>
        </>
      ),
    },
    { label: "Promised", render: (j) => (j.promisedOn ? date(j.promisedOn) : "—") },
    { label: "Technician", render: (j) => j.technicianName ?? "—" },
    { label: "Balance", align: "right", render: (j) => money(j.balance, false) },
    { label: "Status", render: (j) => <Status value={j.status} /> },
  ];
  return (
    <>
      <ListPage<Job>
        title="Repair jobs"
        description="Every device left for repair, from booking to handing back."
        path="/repairs"
        queryKey="repairs"
        columns={columns}
        rowHref={(j) => `/repairs/${j.id}`}
        actions={
          (can(P.REPAIRS_MANAGE) || can(P.POS_SELL)) && (
            <Button onClick={() => setBooking(true)}>
              <Plus /> Book a repair
            </Button>
          )
        }
        filters={[
          { kind: "search", placeholder: "Job no., customer, phone, device or IMEI" },
          {
            kind: "select",
            param: "status",
            label: "Status",
            initial: "OPEN",
            options: [{ value: "OPEN", label: "Open jobs" }, { value: "", label: "All" }, ...REPAIR_STATUSES.map((s) => ({ value: s, label: s === "NOT_REPAIRABLE" ? "Can't repair" : s.charAt(0) + s.slice(1).toLowerCase().replace("_", " ") }))],
          },
        ]}
        empty={{ title: "No repair jobs", description: "Book a device in when a customer leaves it for repair." }}
      >
        {counts.data && (
          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <StatCard label="Being worked on" value={String(counts.data.open)} />
            <StatCard label="Ready to hand back" value={String(counts.data.ready)} tone={counts.data.ready ? "good" : undefined} />
            <StatCard label="Past the promised date" value={String(counts.data.overdue)} tone={counts.data.overdue ? "warn" : undefined} />
          </div>
        )}
      </ListPage>
      {booking && <BookRepair onClose={() => setBooking(false)} />}
    </>
  );
}

function BookRepair({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const router = useShopRouter();
  const tillSessionId = useTillSession();
  const can = useCan();
  const [customer, setCustomer] = React.useState<PickedParty | null>(null);
  const [form, setForm] = React.useState({
    customerName: "",
    customerPhone: "",
    device: "",
    serial: "",
    problem: "",
    accessories: "",
    conditionNote: "",
    estimate: "",
    advance: "",
    advanceMethod: "CASH",
    technicianId: "",
    promisedOn: "",
  });
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const users = useQuery({ queryKey: ["users"], queryFn: () => api<Schemas["UserView"][]>("/users"), enabled: can(P.USERS_VIEW) });
  const save = useMutation({
    mutationFn: () =>
      api<Job>("/repairs", {
        method: "POST",
        body: {
          customerId: customer?.id ?? null,
          customerName: customer ? null : form.customerName,
          customerPhone: form.customerPhone || null,
          device: form.device,
          serial: form.serial || null,
          problem: form.problem,
          accessories: form.accessories || null,
          conditionNote: form.conditionNote || null,
          estimate: form.estimate || null,
          advance: form.advance || null,
          advanceMethod: form.advanceMethod,
          tillSessionId,
          technicianId: form.technicianId ? Number(form.technicianId) : null,
          promisedOn: form.promisedOn || null,
        },
      }),
    onSuccess: (j) => {
      toast.success(`Job ${j.number} booked`);
      client.invalidateQueries({ queryKey: ["repairs"] });
      client.invalidateQueries({ queryKey: ["repair-counts"] });
      router.push(`/repairs/${j.id}`);
    },
  });
  const err = (n: string) => fieldError(save.error, n);
  const needsTill = Number(form.advance || 0) > 0 && !tillSessionId;
  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title="Book a repair"
      description="Print the job card for the customer to keep."
      footer={
        <Button type="submit" form="repair-form" loading={save.isPending} disabled={!form.device.trim() || !form.problem.trim() || needsTill}>
          Book repair
        </Button>
      }
    >
      <form
        id="repair-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="space-y-1.5">
          <p className="text-[13px] font-medium text-muted">Saved customer (or type a new one below)</p>
          <PartyPicker kind="customer" value={customer} onChange={setCustomer} placeholder="Search customers by name or phone" />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          {!customer && (
            <Field label="Customer name" required error={err("customerName")}>
              <Input value={form.customerName} onChange={(e) => set("customerName", e.target.value)} />
            </Field>
          )}
          <Field label={customer ? "Phone for the ready SMS (if different)" : "Phone (for the ready SMS)"} required={!customer} error={err("customerPhone")}>
            <Input value={form.customerPhone} onChange={(e) => set("customerPhone", e.target.value)} inputMode="tel" />
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Device (make and model)" required error={err("device")}>
            <Input value={form.device} onChange={(e) => set("device", e.target.value)} placeholder="Samsung A14" />
          </Field>
          <Field label="IMEI / serial" error={err("serial")}>
            <Input value={form.serial} onChange={(e) => set("serial", e.target.value)} className="font-mono" />
          </Field>
        </div>
        <Field label="Fault" required error={err("problem")}>
          <Textarea value={form.problem} onChange={(e) => set("problem", e.target.value)} placeholder="Screen cracked, touch not working" />
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Left with it" hint="Charger, cover, SIM, memory card…">
            <Input value={form.accessories} onChange={(e) => set("accessories", e.target.value)} />
          </Field>
          <Field label="Condition" hint="Scratches, dents, already broken parts">
            <Input value={form.conditionNote} onChange={(e) => set("conditionNote", e.target.value)} />
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Estimate (Rs)" error={err("estimate")}>
            <Input value={form.estimate} onChange={(e) => set("estimate", e.target.value)} inputMode="decimal" />
          </Field>
          <Field label="Advance taken (Rs)" error={err("advance")} hint={needsTill ? "Open your till first to take an advance." : undefined}>
            <Input value={form.advance} onChange={(e) => set("advance", e.target.value)} inputMode="decimal" />
          </Field>
          <Field label="Advance paid by">
            <Select value={form.advanceMethod} onChange={(e) => set("advanceMethod", e.target.value)}>
              {["CASH", "CARD", "BANK", "JAZZCASH", "EASYPAISA"].map((m) => (
                <option key={m} value={m}>
                  {m === "JAZZCASH" ? "JazzCash" : m === "EASYPAISA" ? "Easypaisa" : m.charAt(0) + m.slice(1).toLowerCase()}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Promised for">
            <Input type="date" value={form.promisedOn} onChange={(e) => set("promisedOn", e.target.value)} />
          </Field>
          {users.data && (
            <Field label="Technician">
              <Select value={form.technicianId} onChange={(e) => set("technicianId", e.target.value)}>
                <option value="">Not given yet</option>
                {users.data
                  .filter((u) => u.status === "ACTIVE")
                  .map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.displayName}
                    </option>
                  ))}
              </Select>
            </Field>
          )}
        </div>
        {save.error && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}
