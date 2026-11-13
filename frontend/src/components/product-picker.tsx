"use client";

import { useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import { Search } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Input } from "@/components/ui/fields";
import { api, ApiError, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";

export type PickedProduct = Schemas["ProductListItem"];

/**
 * Finds a product by name, SKU, formula or part number; a scanned barcode (Enter) picks the product at once. Used by
 * every document screen (purchase orders, goods received, adjustments, counts, transfers, quotations).
 */
export function ProductPicker({
  onPick,
  placeholder = "Scan a barcode or type a product name",
  autoFocus,
  kinds,
}: {
  onPick: (product: PickedProduct) => void;
  placeholder?: string;
  autoFocus?: boolean;
  /** Only these product kinds (e.g. no services in stock documents). */
  kinds?: string[];
}) {
  const [text, setText] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [index, setIndex] = React.useState(0);
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(text.trim()), 150);
    return () => clearTimeout(t);
  }, [text]);

  const results = useQuery({
    queryKey: ["product-picker", debounced],
    queryFn: () => api<Schemas["PageResponseProductListItem"]>(`/products?q=${encodeURIComponent(debounced)}&size=8`),
    enabled: debounced.length >= 2,
  });
  const items = (results.data?.items ?? []).filter((p) => !kinds || kinds.includes(p.kind));

  function pick(product: PickedProduct) {
    if (kinds && !kinds.includes(product.kind)) {
      toast.error(`${product.name} cannot be used here`);
      return;
    }
    onPick(product);
    setText("");
    setDebounced("");
    setOpen(false);
    setIndex(0);
  }

  async function enter() {
    const code = text.trim();
    if (!code) return;
    if (open && items[index]) {
      pick(items[index]);
      return;
    }
    try {
      const found = await api<Schemas["ProductView"]>(`/products/lookup?code=${encodeURIComponent(code)}`);
      pick({
        id: found.id,
        sku: found.sku,
        name: found.name,
        urduName: found.urduName,
        categoryId: found.categoryId,
        categoryName: found.categoryName,
        brandName: found.brandName,
        unitName: found.unitName,
        kind: found.kind,
        price: found.price,
        costAvg: found.costAvg,
        barcode: found.barcodes[0] ?? null,
        active: found.active,
        imageFileId: found.imageFileId,
        formula: found.pharma?.formula ?? null,
      } as PickedProduct);
    }
    catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        if (items.length === 1) pick(items[0]);
        else setOpen(true);
      }
      else {
        toast.error("Could not look up the product");
      }
    }
  }

  return (
    <div className="relative">
      <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
      <Input
        value={text}
        autoFocus={autoFocus}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setIndex(0);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setIndex((i) => Math.min(i + 1, items.length - 1));
          }
          else if (e.key === "ArrowUp") {
            e.preventDefault();
            setIndex((i) => Math.max(i - 1, 0));
          }
          else if (e.key === "Enter") {
            e.preventDefault();
            void enter();
          }
          else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
        placeholder={placeholder}
        aria-label="Find a product"
        className="pl-9"
        autoComplete="off"
      />
      {open && debounced.length >= 2 && (
        <ul role="listbox" className="glass absolute inset-x-0 top-full z-20 mt-1 max-h-72 overflow-y-auto p-1 shadow-xl">
          {items.length === 0 && <li className="px-3 py-3 text-sm text-faint">{results.isFetching ? "Searching…" : "Nothing found"}</li>}
          {items.map((p, i) => (
            <li key={p.id} role="option" aria-selected={i === index}>
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(p)}
                className={cn("flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left", i === index && "bg-raised")}
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">{p.name}</span>
                  <span className="block truncate text-xs text-faint">
                    {p.sku}
                    {p.formula ? ` · ${p.formula}` : ""}
                  </span>
                </span>
                <span className="font-mono text-xs text-muted">{money(p.price)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
