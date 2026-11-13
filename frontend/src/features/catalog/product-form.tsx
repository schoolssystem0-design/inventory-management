"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Plus, Trash2, X } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ScanButton } from "@/components/barcode-scanner";
import { Button } from "@/components/ui/button";
import { AmountInput, Checkbox, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { Panel } from "@/components/ui/layout";
import { ApiError, api, fileUrl, type Schemas } from "@/lib/api/client";
import { label } from "@/lib/format";
import { P, useCan, useFeature } from "@/lib/shop-context";

type Product = Schemas["ProductView"];
type Input = Schemas["ProductInput"];

type PackRow = { unitId: string; factor: string; price: string; barcode: string };

type State = {
  name: string;
  urduName: string;
  sku: string;
  categoryId: string;
  brandId: string;
  unitId: string;
  kind: string;
  price: string;
  wholesalePrice: string;
  minPrice: string;
  costPrice: string;
  taxRate: string;
  reorderLevel: string;
  reorderQty: string;
  weighed: boolean;
  scaleCode: string;
  description: string;
  barcodes: string[];
  imageFileId: number | null;
  packUnits: PackRow[];
  formula: string;
  strength: string;
  dosageForm: string;
  company: string;
  packSize: string;
  schedule: string;
  rack: string;
  registrationNo: string;
  oemNumber: string;
  alternateNumbers: string;
  isbn: string;
  author: string;
  publisher: string;
  edition: string;
  className: string;
  priceChangeReason: string;
};

function initial(p?: Product): State {
  return {
    name: p?.name ?? "",
    urduName: p?.urduName ?? "",
    sku: p?.sku ?? "",
    categoryId: p?.categoryId ? String(p.categoryId) : "",
    brandId: p?.brandId ? String(p.brandId) : "",
    unitId: p ? String(p.unitId) : "",
    kind: p?.kind ?? "STANDARD",
    price: p?.price ?? "",
    wholesalePrice: p?.wholesalePrice ?? "",
    minPrice: p?.minPrice ?? "",
    costPrice: p?.costAvg ?? "",
    taxRate: p?.taxRate ?? "",
    reorderLevel: p?.reorderLevel && p.reorderLevel !== "0.000" ? p.reorderLevel.replace(/\.?0+$/, "") : "",
    reorderQty: p?.reorderQty && p.reorderQty !== "0.000" ? p.reorderQty.replace(/\.?0+$/, "") : "",
    weighed: p?.weighed ?? false,
    scaleCode: p?.scaleCode ?? "",
    description: p?.description ?? "",
    barcodes: p?.barcodes ?? [],
    imageFileId: p?.imageFileId ?? null,
    packUnits: (p?.packUnits ?? []).map((u) => ({
      unitId: String(u.unitId),
      factor: u.factor.replace(/\.?0+$/, ""),
      price: u.price ?? "",
      barcode: u.barcode ?? "",
    })),
    formula: p?.pharma?.formula ?? "",
    strength: p?.pharma?.strength ?? "",
    dosageForm: p?.pharma?.dosageForm ?? "",
    company: p?.pharma?.company ?? "",
    packSize: p?.pharma?.packSize ?? "",
    schedule: p?.pharma?.schedule ?? "OTC",
    rack: p?.pharma?.rack ?? "",
    registrationNo: p?.pharma?.registrationNo ?? "",
    oemNumber: p?.part?.oemNumber ?? "",
    alternateNumbers: (p?.part?.alternateNumbers ?? []).join(", "),
    isbn: p?.book?.isbn ?? "",
    author: p?.book?.author ?? "",
    publisher: p?.book?.publisher ?? "",
    edition: p?.book?.edition ?? "",
    className: p?.book?.className ?? "",
    priceChangeReason: "",
  };
}

const blank = (v: string) => (v.trim() === "" ? undefined : v.trim());

/** Create or edit a product (CAT-01, CAT-02) with the fields of the shop's switched-on modules. */
export function ProductForm({ product, onSaved }: { product?: Product; onSaved: (p: Product) => void }) {
  const can = useCan();
  const has = useFeature();
  const client = useQueryClient();
  const [s, setS] = React.useState<State>(() => initial(product));
  const [newBarcode, setNewBarcode] = React.useState("");
  const [error, setError] = React.useState<ApiError | null>(null);
  const set = <K extends keyof State>(key: K, value: State[K]) => setS((prev) => ({ ...prev, [key]: value }));
  const editing = !!product;

  const units = useQuery({ queryKey: ["units"], queryFn: () => api<Schemas["UnitView"][]>("/units") });
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Schemas["CategoryView"][]>("/categories") });
  const brands = useQuery({ queryKey: ["brands"], queryFn: () => api<Schemas["BrandView"][]>("/brands") });

  // New products start with the shop's first unit.
  const unitId = s.unitId || (units.data?.[0] ? String(units.data[0].id) : "");

  const canPrices = !editing || can(P.CATALOG_PRICES);
  const canCost = can(P.COST_VIEW) && canPrices;

  const kinds = [
    { value: "STANDARD", show: true },
    { value: "SERIAL", show: has("serials") },
    { value: "BATCH", show: has("batches") },
    { value: "VARIANT_PARENT", show: has("variants") },
    { value: "BUNDLE", show: has("bundles") },
    { value: "SERVICE", show: true },
  ].filter((k) => k.show || k.value === s.kind);

  function body(): Input {
    const input: Input = {
      name: s.name,
      urduName: s.urduName,
      categoryId: s.categoryId ? Number(s.categoryId) : editing ? 0 : undefined,
      brandId: s.brandId ? Number(s.brandId) : editing ? 0 : undefined,
      unitId: unitId ? Number(unitId) : undefined,
      kind: s.kind,
      taxRate: editing ? s.taxRate : blank(s.taxRate),
      reorderLevel: s.reorderLevel || "0",
      reorderQty: s.reorderQty || "0",
      weighed: has("weighed") ? s.weighed : undefined,
      scaleCode: has("scale_barcodes") ? s.scaleCode.trim() : undefined,
      description: s.description,
      barcodes: s.barcodes,
      imageFileId: s.imageFileId ?? (editing ? 0 : undefined),
      version: product?.version,
    };
    if (!editing && s.sku.trim()) input.sku = s.sku.trim();
    if (editing && s.sku.trim() && s.sku.trim() !== product.sku) input.sku = s.sku.trim();
    if (canPrices) {
      input.price = s.price;
      input.wholesalePrice = editing ? s.wholesalePrice : blank(s.wholesalePrice);
      input.minPrice = editing ? s.minPrice : blank(s.minPrice);
      if (s.priceChangeReason.trim()) input.priceChangeReason = s.priceChangeReason.trim();
    }
    if (canCost && s.costPrice.trim() && (!editing || s.costPrice !== product.costAvg)) input.costPrice = s.costPrice.trim();
    if (has("pack_units")) {
      input.packUnits = s.packUnits
        .filter((r) => r.unitId && r.factor)
        .map((r) => ({ unitId: Number(r.unitId), factor: r.factor, price: blank(r.price), barcode: blank(r.barcode) }));
    }
    if (has("formula") && (s.formula || s.strength || s.company || s.rack || editing)) {
      input.pharma = {
        formula: s.formula,
        strength: s.strength,
        dosageForm: s.dosageForm,
        company: s.company,
        packSize: s.packSize,
        schedule: s.schedule,
        rack: s.rack,
        registrationNo: s.registrationNo,
      };
    }
    if (has("part_numbers") && (s.oemNumber || s.alternateNumbers || product?.part)) {
      input.part = {
        oemNumber: s.oemNumber,
        alternateNumbers: s.alternateNumbers.split(/[,;]/).map((x) => x.trim()).filter(Boolean),
      };
    }
    if (has("bundles") && (s.isbn || s.author || s.publisher || product?.book)) {
      input.book = { isbn: s.isbn, author: s.author, publisher: s.publisher, edition: s.edition, className: s.className };
    }
    return input;
  }

  const save = useMutation({
    mutationFn: () =>
      editing
        ? api<Product>(`/products/${product.id}`, { method: "PATCH", body: body() })
        : api<Product>("/products", { method: "POST", body: body() }),
    onSuccess: (saved) => {
      setError(null);
      client.invalidateQueries({ queryKey: ["products"] });
      client.invalidateQueries({ queryKey: ["product", saved.id] });
      client.invalidateQueries({ queryKey: ["shop-context"] });
      toast.success(editing ? "Product saved" : "Product added");
      onSaved(saved);
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e : null);
      toast.error(e instanceof Error ? e.message : "Could not save");
    },
  });

  async function uploadImage(file: File) {
    const form = new FormData();
    form.append("file", file);
    try {
      const saved = await api<Schemas["FileInfo"]>("/files?purpose=PRODUCT_IMAGE", { method: "POST", form });
      set("imageFileId", saved.id);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Upload failed");
    }
  }

  function addBarcode(code: string) {
    const c = code.trim();
    if (!c) return;
    if (!s.barcodes.includes(c)) set("barcodes", [...s.barcodes, c]);
    setNewBarcode("");
  }

  const err = (name: string) => error?.field(name);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
      className="grid gap-5 lg:grid-cols-3"
    >
      <div className="space-y-5 lg:col-span-2">
        <Panel title="Basics">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name" required error={err("name")} className="sm:col-span-2">
              <Input value={s.name} onChange={(e) => set("name", e.target.value)} required autoFocus={!editing} maxLength={200} />
            </Field>
            <Field label="Urdu name" hint="Printed on Urdu receipts">
              <Input value={s.urduName} onChange={(e) => set("urduName", e.target.value)} dir="rtl" className="urdu" maxLength={200} />
            </Field>
            <Field label="SKU" hint={editing ? undefined : "Leave empty to number it automatically"} error={err("sku")}>
              <Input value={s.sku} onChange={(e) => set("sku", e.target.value)} className="font-mono" maxLength={40} />
            </Field>
            <Field label="Category" error={err("categoryId")}>
              <Select value={s.categoryId} onChange={(e) => set("categoryId", e.target.value)}>
                <option value="">No category</option>
                {categories.data?.filter((c) => c.active || String(c.id) === s.categoryId).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.parentId ? "  › " : ""}
                    {c.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Brand / company" error={err("brandId")}>
              <Select value={s.brandId} onChange={(e) => set("brandId", e.target.value)}>
                <option value="">No brand</option>
                {brands.data?.filter((b) => b.active || String(b.id) === s.brandId).map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Unit" required error={err("unitId")}>
              <Select value={unitId} onChange={(e) => set("unitId", e.target.value)} required>
                {units.data?.filter((u) => u.active || String(u.id) === unitId).map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="How it is tracked" error={err("kind")}>
              <Select value={s.kind} onChange={(e) => set("kind", e.target.value)}>
                {kinds.map((k) => (
                  <option key={k.value} value={k.value}>
                    {label(k.value)}
                  </option>
                ))}
              </Select>
            </Field>
            {has("weighed") && (
              <Checkbox
                className="sm:col-span-2"
                label="Sold by weight"
                hint="Quantities like 1.25 kg at the till"
                checked={s.weighed}
                onChange={(e) => set("weighed", e.target.checked)}
              />
            )}
            {has("scale_barcodes") && (
              <Field label="Scale code" hint="The item number in the weighing scale (1–5 digits). Labels from the scale then scan at the till." error={err("scaleCode")}>
                <Input value={s.scaleCode} onChange={(e) => set("scaleCode", e.target.value.replace(/\D/g, "").slice(0, 5))} inputMode="numeric" className="font-mono" />
              </Field>
            )}
          </div>
        </Panel>

        <Panel title="Prices" description={!canPrices ? "You can see prices but not change them" : undefined}>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Sale price (Rs)" required error={err("price")}>
              <AmountInput value={s.price} onChange={(e) => set("price", e.target.value)} required disabled={!canPrices} />
            </Field>
            <Field label="Wholesale price" error={err("wholesalePrice")}>
              <AmountInput value={s.wholesalePrice} onChange={(e) => set("wholesalePrice", e.target.value)} disabled={!canPrices} />
            </Field>
            <Field label="Lowest price allowed" hint="Cashiers cannot sell below this" error={err("minPrice")}>
              <AmountInput value={s.minPrice} onChange={(e) => set("minPrice", e.target.value)} disabled={!canPrices} />
            </Field>
            {can(P.COST_VIEW) && (
              <Field
                label={editing ? "Average cost" : "Cost price"}
                hint={editing ? "Kept up to date by purchases; change only to correct it" : "What you paid; profit is worked out from it"}
                error={err("costPrice")}
              >
                <AmountInput value={s.costPrice} onChange={(e) => set("costPrice", e.target.value)} disabled={!canCost} />
              </Field>
            )}
            <Field label="Tax rate %" hint="Empty = the shop's rate" error={err("taxRate")}>
              <AmountInput value={s.taxRate} onChange={(e) => set("taxRate", e.target.value)} />
            </Field>
            {editing && canPrices && (
              <Field label="Reason for price change" hint="Shown in the price history" className="sm:col-span-3">
                <Input value={s.priceChangeReason} onChange={(e) => set("priceChangeReason", e.target.value)} maxLength={200} />
              </Field>
            )}
          </div>
        </Panel>

        <Panel title="Barcodes" description="Scan with a USB scanner into the box, use the camera, or type the code">
          <div className="flex gap-2">
            <Input
              value={newBarcode}
              onChange={(e) => setNewBarcode(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  addBarcode(newBarcode);
                }
              }}
              placeholder="Scan or type a barcode"
              aria-label="New barcode"
              className="font-mono"
            />
            <Button variant="secondary" onClick={() => addBarcode(newBarcode)} aria-label="Add barcode">
              <Plus />
            </Button>
            <ScanButton onCode={addBarcode} />
          </div>
          {err("barcodes") && <p className="mt-2 text-xs text-bad">{err("barcodes")}</p>}
          {s.barcodes.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-2">
              {s.barcodes.map((code) => (
                <li key={code} className="flex items-center gap-1 rounded-full bg-raised py-1 pr-1 pl-3 font-mono text-[13px]">
                  {code}
                  <button
                    type="button"
                    className="rounded-full p-1 text-faint hover:bg-deep hover:text-bad"
                    aria-label={`Remove barcode ${code}`}
                    onClick={() => set("barcodes", s.barcodes.filter((b) => b !== code))}
                  >
                    <X className="size-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        {has("pack_units") && (
          <Panel title="Pack sizes" description="E.g. carton = 24 pieces, box = 10 strips, each with its own price and barcode">
            <div className="space-y-2">
              {s.packUnits.map((row, i) => (
                <div key={i} className="grid grid-cols-[1fr_5rem_6rem_1fr_auto] items-center gap-2">
                  <Select
                    value={row.unitId}
                    aria-label="Pack unit"
                    onChange={(e) => set("packUnits", s.packUnits.map((r, j) => (j === i ? { ...r, unitId: e.target.value } : r)))}
                  >
                    <option value="">Unit…</option>
                    {units.data?.map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.name}
                      </option>
                    ))}
                  </Select>
                  <AmountInput
                    value={row.factor}
                    placeholder="× qty"
                    aria-label="Base units in this pack"
                    onChange={(e) => set("packUnits", s.packUnits.map((r, j) => (j === i ? { ...r, factor: e.target.value } : r)))}
                  />
                  <AmountInput
                    value={row.price}
                    placeholder="Price"
                    aria-label="Pack price"
                    onChange={(e) => set("packUnits", s.packUnits.map((r, j) => (j === i ? { ...r, price: e.target.value } : r)))}
                  />
                  <Input
                    value={row.barcode}
                    placeholder="Pack barcode"
                    aria-label="Pack barcode"
                    className="font-mono"
                    onChange={(e) => set("packUnits", s.packUnits.map((r, j) => (j === i ? { ...r, barcode: e.target.value } : r)))}
                  />
                  <Button variant="ghost" size="icon" aria-label="Remove pack size" onClick={() => set("packUnits", s.packUnits.filter((_, j) => j !== i))}>
                    <Trash2 />
                  </Button>
                </div>
              ))}
              {err("packUnits") && <p className="text-xs text-bad">{err("packUnits")}</p>}
              <Button variant="secondary" size="sm" onClick={() => set("packUnits", [...s.packUnits, { unitId: "", factor: "", price: "", barcode: "" }])}>
                <Plus /> Add pack size
              </Button>
            </div>
          </Panel>
        )}

        {has("formula") && (
          <Panel title="Medicine details">
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Formula (generic)" className="sm:col-span-2">
                <Input value={s.formula} onChange={(e) => set("formula", e.target.value)} />
              </Field>
              <Field label="Strength">
                <Input value={s.strength} onChange={(e) => set("strength", e.target.value)} placeholder="500 mg" />
              </Field>
              <Field label="Form">
                <Input value={s.dosageForm} onChange={(e) => set("dosageForm", e.target.value)} placeholder="Tablet, syrup…" />
              </Field>
              <Field label="Manufacturer">
                <Input value={s.company} onChange={(e) => set("company", e.target.value)} />
              </Field>
              <Field label="Pack size">
                <Input value={s.packSize} onChange={(e) => set("packSize", e.target.value)} placeholder="20 x 10" />
              </Field>
              <Field label="Schedule" error={err("pharma.schedule")}>
                <Select value={s.schedule} onChange={(e) => set("schedule", e.target.value)}>
                  <option value="OTC">Over the counter</option>
                  <option value="RX">Prescription only</option>
                  <option value="CONTROLLED">Controlled</option>
                </Select>
              </Field>
              <Field label="Rack">
                <Input value={s.rack} onChange={(e) => set("rack", e.target.value)} />
              </Field>
              <Field label="Registration no.">
                <Input value={s.registrationNo} onChange={(e) => set("registrationNo", e.target.value)} />
              </Field>
            </div>
          </Panel>
        )}

        {has("part_numbers") && (
          <Panel title="Part numbers">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="OEM part number">
                <Input value={s.oemNumber} onChange={(e) => set("oemNumber", e.target.value)} className="font-mono" />
              </Field>
              <Field label="Alternate numbers" hint="Separate with commas">
                <Input value={s.alternateNumbers} onChange={(e) => set("alternateNumbers", e.target.value)} className="font-mono" />
              </Field>
            </div>
          </Panel>
        )}

        {has("bundles") && (
          <Panel title="Book details" description="For books; leave empty for other products">
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="ISBN">
                <Input value={s.isbn} onChange={(e) => set("isbn", e.target.value)} className="font-mono" />
              </Field>
              <Field label="Author">
                <Input value={s.author} onChange={(e) => set("author", e.target.value)} />
              </Field>
              <Field label="Publisher">
                <Input value={s.publisher} onChange={(e) => set("publisher", e.target.value)} />
              </Field>
              <Field label="Edition">
                <Input value={s.edition} onChange={(e) => set("edition", e.target.value)} />
              </Field>
              <Field label="Class / grade">
                <Input value={s.className} onChange={(e) => set("className", e.target.value)} />
              </Field>
            </div>
          </Panel>
        )}
      </div>

      <div className="space-y-5">
        <Panel title="Picture">
          <div className="space-y-3">
            {s.imageFileId ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={fileUrl(s.imageFileId) ?? ""} alt="" className="aspect-square w-full rounded-xl object-cover" />
            ) : (
              <div className="grid aspect-square w-full place-items-center rounded-xl bg-deep text-faint">
                <ImagePlus className="size-8" aria-hidden />
              </div>
            )}
            <div className="flex gap-2">
              <label className="flex-1 cursor-pointer rounded-xl bg-raised px-3 py-2 text-center text-sm font-semibold hover:bg-[#2b2b2e]">
                {s.imageFileId ? "Change picture" : "Add picture"}
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  className="sr-only"
                  onChange={(e) => e.target.files?.[0] && uploadImage(e.target.files[0])}
                />
              </label>
              {s.imageFileId && (
                <Button variant="ghost" onClick={() => set("imageFileId", null)} aria-label="Remove picture">
                  <Trash2 />
                </Button>
              )}
            </div>
          </div>
        </Panel>
        <Panel title="Stock alerts">
          <div className="grid gap-4">
            <Field label="Reorder level" hint="Warn when stock falls to this" error={err("reorderLevel")}>
              <AmountInput value={s.reorderLevel} onChange={(e) => set("reorderLevel", e.target.value)} />
            </Field>
            <Field label="Usual order quantity" error={err("reorderQty")}>
              <AmountInput value={s.reorderQty} onChange={(e) => set("reorderQty", e.target.value)} />
            </Field>
          </div>
        </Panel>
        <Panel title="Notes">
          <Textarea value={s.description} onChange={(e) => set("description", e.target.value)} aria-label="Notes" maxLength={1000} />
        </Panel>
        <div className="sticky bottom-20 lg:bottom-4">
          <Button type="submit" size="lg" className="w-full" loading={save.isPending}>
            {editing ? "Save changes" : "Add product"}
          </Button>
        </div>
      </div>
    </form>
  );
}
