"use client";

import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import { Boxes, CornerDownLeft, Search } from "lucide-react";
import * as React from "react";
import { SHOP_NAV } from "@/components/shell/nav";
import { api, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { useCan, useFeature, useShopRouter } from "@/lib/shop-context";

type Result = { key: string; label: string; hint?: string; href: string; icon: React.ComponentType<{ className?: string }> };

/** Ctrl K: jump to any page or product (by name, SKU, barcode or formula). */
export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  // Mounted only while open, so each opening starts with an empty search.
  return open ? <Palette onOpenChange={onOpenChange} /> : null;
}

function Palette({ onOpenChange }: { onOpenChange: (open: boolean) => void }) {
  const open = true;
  const [query, setQuery] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [index, setIndex] = React.useState(0);
  const can = useCan();
  const feature = useFeature();
  const router = useShopRouter();

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), 150);
    return () => clearTimeout(t);
  }, [query]);

  const products = useQuery({
    queryKey: ["palette-products", debounced],
    queryFn: () => api<Schemas["PageResponseProductListItem"]>(`/products?q=${encodeURIComponent(debounced)}&size=6`),
    enabled: open && debounced.length >= 2 && can("catalog.view"),
  });

  const q = query.trim().toLowerCase();
  const pages: Result[] = SHOP_NAV.flatMap((g) => g.items)
    .filter((item) => (!item.anyOf || item.anyOf.some(can)) && (!item.module || feature(item.module)))
    .filter((item) => !q || item.label.toLowerCase().includes(q) || (item.keywords ?? "").includes(q))
    .slice(0, q ? 6 : 8)
    .map((item) => ({ key: "page:" + item.href, label: item.label, href: item.href, icon: item.icon, hint: "Page" }));
  const productResults: Result[] = (products.data?.items ?? []).map((p) => ({
    key: "product:" + p.id,
    label: p.name,
    hint: `${p.sku} · ${money(p.price)}`,
    href: `/products/${p.id}`,
    icon: Boxes,
  }));
  const results = [...productResults, ...pages];

  function go(result: Result | undefined) {
    if (!result) return;
    onOpenChange(false);
    router.push(result.href);
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(v) => onOpenChange(v)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/60" />
        <DialogPrimitive.Popup className="glass fixed top-[12vh] left-1/2 z-50 w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 overflow-hidden shadow-2xl outline-none">
          <DialogPrimitive.Title className="sr-only">Search</DialogPrimitive.Title>
          <div className="flex items-center gap-3 border-b border-line px-4">
            <Search className="size-4 text-faint" aria-hidden />
            <input
              autoFocus
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setIndex(0);
              }}
              onKeyDown={(e) => {
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  setIndex((i) => Math.min(i + 1, results.length - 1));
                } else if (e.key === "ArrowUp") {
                  e.preventDefault();
                  setIndex((i) => Math.max(i - 1, 0));
                } else if (e.key === "Enter") {
                  e.preventDefault();
                  go(results[index]);
                }
              }}
              placeholder="Search products or pages…"
              aria-label="Search products or pages"
              className="h-12 flex-1 bg-transparent text-sm outline-none placeholder:text-faint"
            />
            <kbd className="rounded bg-raised px-1.5 py-0.5 font-mono text-[11px] text-faint">Esc</kbd>
          </div>
          <ul className="max-h-80 overflow-y-auto p-2" role="listbox" aria-label="Results">
            {results.length === 0 && <li className="px-3 py-6 text-center text-sm text-faint">Nothing found</li>}
            {results.map((r, i) => (
              <li key={r.key} role="option" aria-selected={i === index}>
                <button
                  onMouseEnter={() => setIndex(i)}
                  onClick={() => go(r)}
                  className={cn("flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left", i === index && "bg-raised")}
                >
                  <r.icon className="size-4 text-faint" />
                  <span className="min-w-0 flex-1 truncate text-sm">{r.label}</span>
                  {r.hint && <span className="truncate text-xs text-faint">{r.hint}</span>}
                  {i === index && <CornerDownLeft className="size-3.5 text-faint" />}
                </button>
              </li>
            ))}
          </ul>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
