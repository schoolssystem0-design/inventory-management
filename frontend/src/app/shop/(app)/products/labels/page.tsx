"use client";

import { useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import JsBarcode from "jsbarcode";
import { Printer, Search, X } from "lucide-react";
import { useSearchParams } from "next/navigation";
import * as React from "react";
import { Suspense } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox, Field, Input, Select } from "@/components/ui/fields";
import { Empty, PageHeader, Panel, Skeleton } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { useShop, useShopHref } from "@/lib/shop-context";

type Label = Schemas["Label"];

const SIZES = {
  small: { name: "Small 38 × 25 mm", box: "w-[38mm] h-[25mm]", text: "text-[7px]", height: 26 },
  medium: { name: "Medium 50 × 30 mm", box: "w-[50mm] h-[30mm]", text: "text-[8px]", height: 32 },
  large: { name: "Large 64 × 34 mm", box: "w-[64mm] h-[34mm]", text: "text-[9px]", height: 36 },
} as const;

/** CAT-07: barcode labels in three sizes; EAN-13/UPC print in their own format, everything else as Code 128. */
export default function LabelsPage() {
  return (
    <Suspense fallback={<Skeleton className="h-64" />}>
      <Labels />
    </Suspense>
  );
}

function Labels() {
  const params = useSearchParams();
  const shop = useShop();
  const toHref = useShopHref();
  const [ids, setIds] = React.useState<number[]>(() =>
    (params.get("ids") ?? "")
      .split(",")
      .map(Number)
      .filter((n) => n > 0),
  );
  const [copies, setCopies] = React.useState<Record<number, number>>({});
  const [size, setSize] = React.useState<keyof typeof SIZES>("medium");
  const [showPrice, setShowPrice] = React.useState(true);
  const [showShop, setShowShop] = React.useState(false);
  const [search, setSearch] = React.useState("");

  const labels = useQuery({
    queryKey: ["labels", ids.join(",")],
    queryFn: () => api<Label[]>("/labels", { method: "POST", body: { items: ids.map((productId) => ({ productId, copies: 1 })) } }),
    enabled: ids.length > 0,
  });
  const found = useQuery({
    queryKey: ["label-search", search],
    queryFn: () => api<Schemas["PageResponseProductListItem"]>(`/products?size=8&q=${encodeURIComponent(search)}`),
    enabled: search.trim().length >= 2,
  });

  const list = labels.data ?? [];
  const total = list.reduce((sum, l) => sum + (copies[l.productId] ?? 1), 0);
  const s = SIZES[size];

  return (
    <>
      <div className="no-print">
        <PageHeader
          title="Barcode labels"
          back={{ href: toHref("/products"), label: "Products" }}
          actions={
            <Button onClick={() => window.print()} disabled={total === 0}>
              <Printer /> Print {total} label{total === 1 ? "" : "s"}
            </Button>
          }
        />
        <div className="grid gap-5 lg:grid-cols-3">
          <Panel title="Products" className="lg:col-span-2">
            <div className="relative">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
              <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Add a product by name or barcode" className="pl-9" aria-label="Find a product" />
            </div>
            {search.trim().length >= 2 && (found.data?.items.length ?? 0) > 0 && (
              <ul className="mt-2 space-y-1">
                {found.data!.items.map((p) => (
                  <li key={p.id}>
                    <button
                      className="w-full rounded-lg px-3 py-2 text-left text-sm hover:bg-raised"
                      onClick={() => {
                        if (!ids.includes(p.id)) setIds([...ids, p.id]);
                        setSearch("");
                      }}
                    >
                      {p.name} <span className="font-mono text-xs text-faint">{p.barcode ?? p.sku}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {ids.length === 0 ? (
              <Empty title="No products chosen" description="Search above, or tick products in the product list and press Print labels." />
            ) : labels.isPending ? (
              <Skeleton className="mt-4 h-24" />
            ) : (
              <ul className="mt-4 divide-y divide-line">
                {list.map((l) => (
                  <li key={l.productId} className="flex items-center gap-3 py-2">
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm">{l.name}</p>
                      <p className="font-mono text-xs text-faint">
                        {l.code} · {l.format}
                      </p>
                    </div>
                    <Input
                      type="number"
                      min={1}
                      max={500}
                      value={copies[l.productId] ?? 1}
                      onChange={(e) => setCopies({ ...copies, [l.productId]: Math.max(1, Math.min(500, Number(e.target.value) || 1)) })}
                      className="w-20 text-right"
                      aria-label={`Copies of ${l.name}`}
                    />
                    <button className="rounded-lg p-2 text-faint hover:bg-raised hover:text-bad" aria-label={`Remove ${l.name}`} onClick={() => setIds(ids.filter((i) => i !== l.productId))}>
                      <X className="size-4" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <Panel title="Label">
            <div className="space-y-4">
              <Field label="Size">
                <Select value={size} onChange={(e) => setSize(e.target.value as keyof typeof SIZES)}>
                  {Object.entries(SIZES).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v.name}
                    </option>
                  ))}
                </Select>
              </Field>
              <Checkbox label="Price" checked={showPrice} onChange={(e) => setShowPrice(e.target.checked)} />
              <Checkbox label="Shop name" checked={showShop} onChange={(e) => setShowShop(e.target.checked)} />
            </div>
          </Panel>
        </div>
        <h2 className="mt-6 mb-2 text-sm font-semibold text-muted">Preview</h2>
      </div>

      <div className="flex flex-wrap gap-[2mm] rounded-xl bg-white p-[2mm] print:rounded-none print:p-0">
        {list.flatMap((l) =>
          Array.from({ length: copies[l.productId] ?? 1 }, (_, n) => (
            <div
              key={`${l.productId}-${n}`}
              className={cn(s.box, "flex flex-col items-center justify-between overflow-hidden rounded-[1mm] border border-dashed border-slate-300 p-[1.2mm] text-black print:break-inside-avoid print:border-slate-200")}
            >
              {showShop && <div className="w-full truncate text-center text-[6px] tracking-wide text-slate-500 uppercase">{shop.shop.name}</div>}
              <div className={cn("line-clamp-2 w-full text-center leading-tight font-semibold", s.text)}>{l.name}</div>
              <BarcodeSvg value={l.code} format={l.format} height={s.height} />
              {showPrice && <div className="text-[10px] font-bold">{money(l.price)}</div>}
            </div>
          )),
        )}
      </div>
      <style>{`@media print { @page { margin: 5mm; } }`}</style>
    </>
  );
}

function BarcodeSvg({ value, format, height }: { value: string; format: string; height: number }) {
  const ref = React.useRef<SVGSVGElement>(null);
  React.useEffect(() => {
    if (!ref.current) return;
    const options = { height, width: 1.3, fontSize: 10, margin: 0, displayValue: true, background: "#ffffff" };
    try {
      JsBarcode(ref.current, value, { ...options, format });
    } catch {
      JsBarcode(ref.current, value, { ...options, format: "CODE128" });
    }
  }, [value, format, height]);
  return <svg ref={ref} className="max-w-full" aria-label={`Barcode ${value}`} />;
}
