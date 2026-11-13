"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Camera, Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { type Column, daysAgoIso, ListPage, todayIso } from "@/components/list-page";
import { ProductPicker, type PickedProduct } from "@/components/product-picker";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { api, ApiError, errorMessage, fileUrl, type Schemas } from "@/lib/api/client";
import { dateTime, label, money } from "@/lib/format";
import { isMoney } from "@/lib/money";
import { P, useBranch, useCan } from "@/lib/shop-context";

type Row = Schemas["BuybackView"];

/** Used phones bought from customers (MOB-03): seller's CNIC and photos are kept as the law requires. */
export default function BuybacksPage() {
  const can = useCan();
  const [adding, setAdding] = React.useState(false);
  const columns: Column<Row>[] = [
    { label: "Number", render: (b) => <span className="font-mono font-medium">{b.number}</span> },
    { label: "When", render: (b) => <span className="text-muted">{dateTime(b.at)}</span> },
    {
      label: "Phone",
      render: (b) => (
        <>
          {b.productName}
          <span className="block font-mono text-xs text-faint">{b.serial}</span>
        </>
      ),
    },
    {
      label: "Seller",
      render: (b) => (
        <>
          {b.sellerName}
          <span className="block text-xs text-faint">{[b.sellerCnic, b.sellerPhone].filter(Boolean).join(" · ")}</span>
        </>
      ),
    },
    { label: "Condition", render: (b) => label(b.condition) },
    { label: "Paid", render: (b) => money(b.price, false), align: "right" },
    {
      label: "Photos",
      render: (b) => (
        <span className="flex gap-2">
          {b.itemFileId && (
            <a href={fileUrl(b.itemFileId) ?? "#"} target="_blank" rel="noreferrer" className="text-xs text-aqua">
              Phone
            </a>
          )}
          {b.cnicFileId && (
            <a href={fileUrl(b.cnicFileId) ?? "#"} target="_blank" rel="noreferrer" className="text-xs text-aqua">
              CNIC
            </a>
          )}
        </span>
      ),
    },
  ];
  return (
    <>
      <ListPage<Row>
        title="Used phones bought"
        description="Each phone goes into stock with its IMEI; the seller's CNIC and photos stay on record."
        path="/buybacks"
        queryKey="buybacks"
        columns={columns}
        actions={
          can(P.BUYBACK_MANAGE) && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> Buy a used phone
            </Button>
          )
        }
        filters={[{ kind: "dates", from: daysAgoIso(30), to: todayIso() }]}
        empty={{ title: "No used phones bought in these dates" }}
      />
      {adding && <BuybackDialog onClose={() => setAdding(false)} />}
    </>
  );
}

function PhotoInput({ label: text, file, onFile }: { label: string; file: File | null; onFile: (f: File | null) => void }) {
  const [preview, setPreview] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    const t = window.setTimeout(() => setPreview(url), 0);
    return () => {
      window.clearTimeout(t);
      URL.revokeObjectURL(url);
    };
  }, [file]);
  return (
    <label className="tile flex cursor-pointer flex-col items-center justify-center gap-2 p-3 text-center text-sm text-muted">
      {file && preview ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={preview} alt={text} className="h-24 rounded-lg object-cover" />
      ) : (
        <Camera className="size-6 text-faint" aria-hidden />
      )}
      {text}
      <input type="file" accept="image/*" capture="environment" className="sr-only" onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
    </label>
  );
}

function BuybackDialog({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const [branch] = useBranch();
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  const session = sessions.data?.find((s) => s.branchId === branch?.id) ?? null;
  const [product, setProduct] = React.useState<PickedProduct | null>(null);
  const [f, setF] = React.useState({ sellerName: "", sellerCnic: "", sellerPhone: "", sellerAddress: "", serial: "", serial2: "", storage: "", colour: "", condition: "USED", ptaStatus: "APPROVED", price: "", method: "CASH", notes: "" });
  const [cnic, setCnic] = React.useState<File | null>(null);
  const [item, setItem] = React.useState<File | null>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const upload = async (file: File, purpose: string) => {
    const data = new FormData();
    data.append("file", file);
    return (await api<Schemas["FileInfo"]>(`/files?purpose=${purpose}`, { method: "POST", form: data })).id;
  };
  const save = useMutation({
    mutationFn: async () => {
      const [cnicFileId, itemFileId] = await Promise.all([cnic ? upload(cnic, "CNIC_PHOTO") : null, item ? upload(item, "DEVICE_PHOTO") : null]);
      return api<Row>("/buybacks", {
        method: "POST",
        body: {
          productId: product?.id,
          sellerName: f.sellerName,
          sellerCnic: f.sellerCnic,
          sellerPhone: f.sellerPhone || null,
          sellerAddress: f.sellerAddress || null,
          cnicFileId,
          itemFileId,
          price: f.price,
          method: f.method,
          notes: f.notes || null,
          tillSessionId: session?.id ?? null,
          unit: { serial: f.serial, serial2: f.serial2 || null, storage: f.storage || null, colour: f.colour || null, condition: f.condition, ptaStatus: f.ptaStatus },
        },
      });
    },
    onSuccess: (b) => {
      toast.success(`${b.number}: ${b.productName} added to stock`);
      client.invalidateQueries({ queryKey: ["buybacks"] });
      onClose();
    },
  });
  const err = (k: string) => (save.error instanceof ApiError ? save.error.field(k) : undefined);
  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title="Buy a used phone"
      description={session ? `Paid from ${session.tillName}.` : "Cash is paid from your open till — open it first for a cash purchase."}
      footer={
        <Button type="submit" form="buyback-form" loading={save.isPending} disabled={!product || !isMoney(f.price) || !f.serial.trim()}>
          Buy and add to stock
        </Button>
      }
    >
      <form
        id="buyback-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Model" error={err("productId")}>
          {product ? (
            <div className="field flex items-center justify-between">
              {product.name}
              <button type="button" className="text-xs text-faint" onClick={() => setProduct(null)}>
                Change
              </button>
            </div>
          ) : (
            <ProductPicker onPick={setProduct} kinds={["SERIAL"]} placeholder="Find the phone model" />
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="IMEI" required error={err("unit.serial")}>
            <Input value={f.serial} onChange={set("serial")} className="font-mono" />
          </Field>
          <Field label="Second IMEI">
            <Input value={f.serial2} onChange={set("serial2")} className="font-mono" />
          </Field>
          <Field label="Storage / colour">
            <div className="flex gap-1">
              <Input value={f.storage} onChange={set("storage")} placeholder="128GB" />
              <Input value={f.colour} onChange={set("colour")} placeholder="Black" />
            </div>
          </Field>
          <Field label="Condition">
            <Select value={f.condition} onChange={set("condition")}>
              <option value="USED">Used</option>
              <option value="BOX_OPEN">Box open</option>
              <option value="NEW">New</option>
            </Select>
          </Field>
          <Field label="PTA">
            <Select value={f.ptaStatus} onChange={set("ptaStatus")}>
              <option value="APPROVED">PTA approved</option>
              <option value="NON_PTA">Non-PTA</option>
              <option value="PATCHED">Patched</option>
            </Select>
          </Field>
          <Field label="Price paid" required error={err("price")}>
            <AmountInput value={f.price} onChange={set("price")} />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Seller's name" required error={err("sellerName")}>
            <Input value={f.sellerName} onChange={set("sellerName")} />
          </Field>
          <Field label="Seller's CNIC" required error={err("sellerCnic")}>
            <Input value={f.sellerCnic} onChange={set("sellerCnic")} placeholder="xxxxx-xxxxxxx-x" inputMode="numeric" />
          </Field>
          <Field label="Seller's phone">
            <Input value={f.sellerPhone} onChange={set("sellerPhone")} inputMode="tel" />
          </Field>
          <Field label="Address" className="sm:col-span-2">
            <Input value={f.sellerAddress} onChange={set("sellerAddress")} />
          </Field>
          <Field label="Paid by">
            <Select value={f.method} onChange={set("method")}>
              <option value="CASH">Cash</option>
              <option value="BANK">Bank transfer</option>
              <option value="JAZZCASH">JazzCash</option>
              <option value="EASYPAISA">Easypaisa</option>
            </Select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <PhotoInput label="Photo of the CNIC" file={cnic} onFile={setCnic} />
          <PhotoInput label="Photo of the phone" file={item} onFile={setItem} />
        </div>
        {(err("cnicFileId") || err("itemFileId")) && <p className="text-sm text-bad">{err("cnicFileId") ?? err("itemFileId")}</p>}
        <Field label="Notes">
          <Textarea value={f.notes} onChange={set("notes")} className="min-h-10" />
        </Field>
        {save.error && !(save.error instanceof ApiError && save.error.fieldErrors.length) && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}
