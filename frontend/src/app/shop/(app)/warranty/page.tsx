"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/fields";
import { Badge, DetailList } from "@/components/ui/layout";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime } from "@/lib/format";
import { P, ShopLink, useCan } from "@/lib/shop-context";

type Row = Schemas["WarrantyClaimView"];
const STATUSES = ["OPEN", "SENT", "REPAIRED", "REPLACED", "REJECTED", "CLOSED"];

/** Warranty claims (MOB-04): looked up by IMEI or invoice; in or out of warranty is decided from the sale. */
export default function WarrantyPage() {
  const can = useCan();
  const [adding, setAdding] = React.useState(false);
  const [open, setOpen] = React.useState<Row | null>(null);
  const columns: Column<Row>[] = [
    {
      label: "Claim",
      render: (c) => (
        <button className="text-left font-mono font-medium hover:text-aqua" onClick={() => setOpen(c)}>
          {c.number}
        </button>
      ),
    },
    { label: "When", render: (c) => <span className="text-muted">{dateTime(c.at)}</span> },
    {
      label: "Item",
      render: (c) => (
        <>
          {c.productName}
          {c.serial && <span className="block font-mono text-xs text-faint">{c.serial}</span>}
        </>
      ),
    },
    { label: "Customer", render: (c) => c.customerName ?? "—" },
    { label: "Warranty", render: (c) => (c.inWarranty ? <Badge tone="good">Until {date(c.warrantyUntil)}</Badge> : <Badge tone="bad">Expired</Badge>) },
    { label: "Status", render: (c) => <Status value={c.status} /> },
  ];
  return (
    <>
      <ListPage<Row>
        title="Warranty claims"
        description="Faulty items brought back. Track them while they are with the company."
        path="/warranty-claims"
        queryKey="warranty-claims"
        columns={columns}
        actions={
          (can(P.WARRANTY_MANAGE) || can(P.POS_SELL)) && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> New claim
            </Button>
          )
        }
        filters={[{ kind: "select", param: "status", label: "Status", options: [{ value: "", label: "All" }, ...STATUSES.map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))] }]}
        empty={{ title: "No warranty claims" }}
      />
      {adding && <NewClaim onClose={() => setAdding(false)} />}
      {open && <ClaimDialog claim={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function NewClaim({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const [serial, setSerial] = React.useState("");
  const [issue, setIssue] = React.useState("");
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  const lookup = useQuery({
    queryKey: ["serial", serial.trim()],
    queryFn: () => api<Schemas["SerialView"]>(`/serials/${encodeURIComponent(serial.trim())}`),
    enabled: serial.trim().length >= 6,
    retry: false,
  });
  const s = lookup.data;
  const save = useMutation({
    mutationFn: () =>
      api<Row>("/warranty-claims", {
        method: "POST",
        body: { serial: serial.trim(), issue, productId: s?.productId ?? null, saleId: s?.saleId ?? null, customerName: name || s?.customerName || null, customerPhone: phone || null },
      }),
    onSuccess: (c) => {
      toast.success(`Claim ${c.number} opened`);
      client.invalidateQueries({ queryKey: ["warranty-claims"] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="New warranty claim"
      footer={
        <Button type="submit" form="claim-form" loading={save.isPending} disabled={!serial.trim() || !issue.trim()}>
          Open claim
        </Button>
      }
    >
      <form
        id="claim-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="IMEI / serial" error={save.error instanceof ApiError ? save.error.field("serial") : undefined}>
          <Input value={serial} onChange={(e) => setSerial(e.target.value)} autoFocus className="font-mono" />
        </Field>
        {s && (
          <div className="rounded-xl bg-raised/60 p-3 text-sm">
            <p className="font-medium">{s.productName}</p>
            <p className="text-muted">
              {s.saleNumber ? `Sold ${dateTime(s.soldAt)} on ${s.saleNumber} to ${s.customerName ?? "walk-in"}` : "Not sold by this shop"}
            </p>
            {s.warrantyUntil && <p className={s.underWarranty ? "text-good" : "text-bad"}>{s.underWarranty ? `Under warranty till ${date(s.warrantyUntil)}` : `Warranty ended ${date(s.warrantyUntil)}`}</p>}
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Customer name">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={s?.customerName ?? ""} />
          </Field>
          <Field label="Phone">
            <Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" />
          </Field>
        </div>
        <Field label="What is wrong" required>
          <Textarea value={issue} onChange={(e) => setIssue(e.target.value)} />
        </Field>
        {save.error && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}

function ClaimDialog({ claim, onClose }: { claim: Row; onClose: () => void }) {
  const client = useQueryClient();
  const can = useCan();
  const [status, setStatus] = React.useState(claim.status);
  const [resolution, setResolution] = React.useState(claim.resolution ?? "");
  const save = useMutation({
    mutationFn: () => api(`/warranty-claims/${claim.id}`, { method: "PATCH", body: { status, resolution: resolution || null } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["warranty-claims"] });
      onClose();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Claim ${claim.number}`}
      footer={
        can(P.WARRANTY_MANAGE) && (
          <Button onClick={() => save.mutate()} loading={save.isPending}>
            Save
          </Button>
        )
      }
    >
      <div className="space-y-4">
        <DetailList
          items={[
            { label: "Item", value: `${claim.productName}${claim.serial ? ` · ${claim.serial}` : ""}` },
            { label: "Customer", value: [claim.customerName, claim.customerPhone].filter(Boolean).join(" · ") || null },
            { label: "Sale", value: claim.saleId ? <ShopLink href={`/sales/${claim.saleId}`} className="text-aqua">{claim.saleNumber}</ShopLink> : null },
            { label: "Warranty", value: claim.inWarranty ? `Until ${date(claim.warrantyUntil)}` : "Out of warranty" },
            { label: "Issue", value: claim.issue },
            { label: "Opened by", value: `${claim.actorName} · ${dateTime(claim.at)}` },
          ]}
        />
        {can(P.WARRANTY_MANAGE) && (
          <>
            <Field label="Status">
              <Select value={status} onChange={(e) => setStatus(e.target.value)}>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s.charAt(0) + s.slice(1).toLowerCase()}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Resolution">
              <Textarea value={resolution} onChange={(e) => setResolution(e.target.value)} />
            </Field>
          </>
        )}
      </div>
    </Dialog>
  );
}
