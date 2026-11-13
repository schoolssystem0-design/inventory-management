"use client";

import { useQuery } from "@tanstack/react-query";
import { Camera, Gift, Pill, X } from "lucide-react";
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/fields";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import type { Cart, PrescriptionForm, Totals } from "./cart";

/**
 * Whether the bill needs a prescription before payment (MED-08/09): controlled medicines always, prescription (Rx)
 * medicines when the shop asks for it.
 */
export function prescriptionNeed(cart: Cart, rxRequired: boolean): { needed: boolean; controlled: boolean; names: string[] } {
  const lines = cart.lines.filter((l) => l.product.schedule === "CONTROLLED" || (rxRequired && l.product.schedule === "RX"));
  return { needed: lines.length > 0, controlled: lines.some((l) => l.product.schedule === "CONTROLLED"), names: [...new Set(lines.map((l) => l.product.name))] };
}

/** Coupon on the bill: typed, checked with the server, shown with what it took off. */
export function CouponControl({ cart, totals, onApply, onRemove }: { cart: Cart; totals: Totals; onApply: (c: { code: string; offerId: number; name: string }) => void; onRemove: () => void }) {
  const [open, setOpen] = React.useState(false);
  const [code, setCode] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  if (cart.coupon) {
    return (
      <div className="flex items-center justify-between gap-2 text-sm">
        <span className="inline-flex items-center gap-1.5 text-good">
          <Gift className="size-4" /> Coupon <span className="font-mono">{cart.coupon.code}</span>
        </span>
        {!totals.couponUsed && <span className="text-xs text-warn">no matching items yet</span>}
        <button className="rounded p-0.5 text-faint hover:text-bad" onClick={onRemove} aria-label="Remove coupon">
          <X className="size-4" />
        </button>
      </div>
    );
  }
  if (!open) {
    return (
      <button className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-aqua" onClick={() => setOpen(true)}>
        <Gift className="size-4" /> Add a coupon
      </button>
    );
  }
  const apply = async () => {
    setBusy(true);
    setError(null);
    try {
      const c = await api<{ offerId: number; name: string; summary: string }>(`/pos/coupons/${encodeURIComponent(code.trim())}`);
      onApply({ code: code.trim().toUpperCase(), offerId: c.offerId, name: c.name });
      setOpen(false);
      setCode("");
    }
    catch (e) {
      setError(e instanceof ApiError ? e.message : errorMessage(e));
    }
    finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="flex items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        void apply();
      }}
    >
      <Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="Coupon code" aria-label="Coupon code" className="h-8 flex-1 font-mono" autoFocus />
      <Button size="sm" type="submit" loading={busy} disabled={code.trim().length < 3}>
        Apply
      </Button>
      <button type="button" className="text-faint hover:text-ink" onClick={() => setOpen(false)} aria-label="Close coupon">
        <X className="size-4" />
      </button>
      {error && <span className="sr-only" role="alert">{error}</span>}
      {error && <p className="basis-full text-xs text-bad">{error}</p>}
    </form>
  );
}

/** The prescription for the bill: patient, doctor and a photo. */
export function PrescriptionDialog({
  open,
  names,
  controlled,
  initial,
  onClose,
  onSave,
}: {
  open: boolean;
  names: string[];
  controlled: boolean;
  initial: PrescriptionForm | null;
  onClose: () => void;
  onSave: (p: PrescriptionForm) => void;
}) {
  if (!open) return null;
  return <PrescriptionBody names={names} controlled={controlled} initial={initial} onClose={onClose} onSave={onSave} />;
}

function PrescriptionBody({ names, controlled, initial, onClose, onSave }: { names: string[]; controlled: boolean; initial: PrescriptionForm | null; onClose: () => void; onSave: (p: PrescriptionForm) => void }) {
  const [f, setF] = React.useState<PrescriptionForm>(initial ?? { patientName: "", patientAge: "", patientPhone: "", doctorName: "", doctorRegNo: "", fileId: null });
  const [uploading, setUploading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const set = (k: keyof PrescriptionForm, v: string) => setF((x) => ({ ...x, [k]: v }));
  const upload = async (file: File | null) => {
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const data = new FormData();
      data.append("file", file);
      const info = await api<Schemas["FileInfo"]>("/files?purpose=PRESCRIPTION", { method: "POST", form: data });
      setF((x) => ({ ...x, fileId: info.id }));
    }
    catch (e) {
      setError(errorMessage(e));
    }
    finally {
      setUploading(false);
    }
  };
  const ok = f.patientName.trim() && f.doctorName.trim();
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Prescription"
      description={`${names.join(", ")} ${names.length === 1 ? "needs" : "need"} a prescription${controlled ? ". Controlled medicine: the pharmacist approves with their PIN when you pay." : "."}`}
      footer={
        <Button
          type="submit"
          form="rx-form"
          disabled={!ok || uploading}
          loading={uploading}
        >
          <Pill /> Save and pay
        </Button>
      }
    >
      <form
        id="rx-form"
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (ok) onSave({ ...f, patientAge: f.patientAge || null, patientPhone: f.patientPhone || null, doctorRegNo: f.doctorRegNo || null });
        }}
      >
        <div className="grid grid-cols-[1fr_6rem] gap-3">
          <Field label="Patient" required>
            <Input value={f.patientName} onChange={(e) => set("patientName", e.target.value)} autoFocus />
          </Field>
          <Field label="Age">
            <Input value={f.patientAge ?? ""} onChange={(e) => set("patientAge", e.target.value)} />
          </Field>
        </div>
        <Field label="Patient phone">
          <Input value={f.patientPhone ?? ""} onChange={(e) => set("patientPhone", e.target.value)} inputMode="tel" />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Doctor" required>
            <Input value={f.doctorName} onChange={(e) => set("doctorName", e.target.value)} />
          </Field>
          <Field label="PMC / registration no.">
            <Input value={f.doctorRegNo ?? ""} onChange={(e) => set("doctorRegNo", e.target.value)} />
          </Field>
        </div>
        <label className="flex cursor-pointer items-center gap-2 text-sm text-aqua">
          <Camera className="size-4" /> {f.fileId ? "Photo added — take again" : "Photo of the prescription"}
          <input type="file" accept="image/*,application/pdf" capture="environment" className="sr-only" onChange={(e) => void upload(e.target.files?.[0] ?? null)} />
        </label>
        {error && <p className="text-sm text-bad">{error}</p>}
      </form>
    </Dialog>
  );
}

/** The customer's points for paying with them at the till (CUS-06). */
export function useLoyalty(customerId: number | null | undefined, on: boolean) {
  return useQuery({
    queryKey: ["loyalty", customerId],
    queryFn: () => api<Schemas["LoyaltyAccount"]>(`/customers/${customerId}/loyalty`),
    enabled: on && !!customerId,
    staleTime: 10_000,
  });
}

/** "Rs 120 off (2 offers)" line under the bill. */
export function offersText(t: Totals): string | null {
  const count = t.lines.filter((l) => l.offer.offerId != null).length + (t.billOffer.offerId != null ? 1 : 0);
  if (!count) return null;
  return `${money(t.offers)} off · ${count} offer${count === 1 ? "" : "s"}`;
}
