"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Barcode, Download, FileUp, Percent, Plus, Search } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ScanButton } from "@/components/barcode-scanner";
import { Button, LinkButton } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/fields";
import { Badge, Empty, ErrorState, PageHeader, Pager, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { label, money } from "@/lib/format";
import { P, ShopLink, useCan, useShopHref, useShopRouter } from "@/lib/shop-context";

const SIZE = 50;

export default function ProductsPage() {
  const can = useCan();
  const toHref = useShopHref();
  const router = useShopRouter();
  const [q, setQ] = React.useState("");
  const [search, setSearch] = React.useState("");
  const [categoryId, setCategoryId] = React.useState("");
  const [brandId, setBrandId] = React.useState("");
  const [active, setActive] = React.useState("true");
  const [page, setPage] = React.useState(0);
  const [selected, setSelected] = React.useState<Set<number>>(new Set());

  React.useEffect(() => {
    const t = setTimeout(() => {
      setSearch(q.trim());
      setPage(0);
    }, 200);
    return () => clearTimeout(t);
  }, [q]);

  const params = new URLSearchParams({ page: String(page), size: String(SIZE), active });
  if (search) params.set("q", search);
  if (categoryId) params.set("categoryId", categoryId);
  if (brandId) params.set("brandId", brandId);

  const products = useQuery({
    queryKey: ["products", params.toString()],
    queryFn: () => api<Schemas["PageResponseProductListItem"]>(`/products?${params}`),
    placeholderData: keepPreviousData,
  });
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Schemas["CategoryView"][]>("/categories") });
  const brands = useQuery({ queryKey: ["brands"], queryFn: () => api<Schemas["BrandView"][]>("/brands") });

  const showCost = can(P.COST_VIEW);
  const items = products.data?.items ?? [];

  async function exportExcel() {
    try {
      saveBlob(await apiBlob("/products/export"), "products.xlsx");
    } catch (e) {
      toast.error(errorMessage(e));
    }
  }

  function toggle(id: number) {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <>
      <PageHeader
        title="Products"
        description={products.data ? `${products.data.total.toLocaleString()} products` : "Your catalogue"}
        actions={
          <>
            {can(P.CATALOG_IMPORT) && (
              <LinkButton href={toHref("/products/import")} variant="secondary">
                <FileUp /> Import
              </LinkButton>
            )}
            {can(P.CATALOG_EXPORT) && (
              <Button variant="secondary" onClick={exportExcel}>
                <Download /> Export
              </Button>
            )}
            {can(P.CATALOG_PRICES) && (
              <LinkButton href={toHref("/products/bulk-price")} variant="secondary">
                <Percent /> Prices
              </LinkButton>
            )}
            {can(P.CATALOG_MANAGE) && (
              <LinkButton href={toHref("/products/new")}>
                <Plus /> New product
              </LinkButton>
            )}
          </>
        }
      />

      <Panel padded={false}>
        <div className="flex flex-wrap items-center gap-2 border-b border-line p-3">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Name, SKU, barcode, formula…"
              aria-label="Search products"
              className="pl-9"
              autoFocus
            />
          </div>
          <ScanButton
            onCode={async (code) => {
              try {
                const found = await api<Schemas["ProductView"]>(`/products/lookup?code=${encodeURIComponent(code)}`);
                router.push(`/products/${found.id}`);
              } catch {
                setQ(code);
                toast.info(`No product with barcode ${code}`);
              }
            }}
          />
          <Select value={categoryId} onChange={(e) => { setCategoryId(e.target.value); setPage(0); }} aria-label="Category" className="w-auto min-w-36">
            <option value="">All categories</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </Select>
          <Select value={brandId} onChange={(e) => { setBrandId(e.target.value); setPage(0); }} aria-label="Brand" className="w-auto min-w-32">
            <option value="">All brands</option>
            {brands.data?.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </Select>
          <Select value={active} onChange={(e) => { setActive(e.target.value); setPage(0); }} aria-label="Status" className="w-auto">
            <option value="true">Active</option>
            <option value="false">Deactivated</option>
          </Select>
        </div>

        {selected.size > 0 && can(P.LABELS_PRINT) && (
          <div className="flex items-center justify-between gap-3 border-b border-line bg-aqua/10 px-4 py-2 text-sm">
            <span>{selected.size} selected</span>
            <div className="flex gap-2">
              <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
                Clear
              </Button>
              <LinkButton size="sm" href={toHref(`/products/labels?ids=${[...selected].join(",")}`)}>
                <Barcode /> Print labels
              </LinkButton>
            </div>
          </div>
        )}

        {products.isPending ? (
          <TableSkeleton />
        ) : products.isError ? (
          <ErrorState message={errorMessage(products.error)} onRetry={() => products.refetch()} />
        ) : items.length === 0 ? (
          <Empty
            title={search ? "No products match" : "No products yet"}
            description={search ? "Try a different word or barcode." : "Add products one by one or import them from Excel."}
            action={
              !search && can(P.CATALOG_IMPORT) ? (
                <LinkButton href={toHref("/products/import")}>
                  <FileUp /> Import from Excel
                </LinkButton>
              ) : undefined
            }
          />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  {can(P.LABELS_PRINT) && (
                    <Th className="w-10">
                      <input
                        type="checkbox"
                        aria-label="Select all on this page"
                        className="accent-[#4fbfae]"
                        checked={items.every((p) => selected.has(p.id))}
                        onChange={(e) =>
                          setSelected((s) => {
                            const next = new Set(s);
                            items.forEach((p) => (e.target.checked ? next.add(p.id) : next.delete(p.id)));
                            return next;
                          })
                        }
                      />
                    </Th>
                  )}
                  <Th>Product</Th>
                  <Th>Category</Th>
                  <Th>Barcode</Th>
                  {showCost && <Th align="right">Cost</Th>}
                  <Th align="right">Price</Th>
                </tr>
              </thead>
              <tbody>
                {items.map((p) => (
                  <tr key={p.id} className="hover:bg-raised/40">
                    {can(P.LABELS_PRINT) && (
                      <Td>
                        <input
                          type="checkbox"
                          className="accent-[#4fbfae]"
                          aria-label={`Select ${p.name}`}
                          checked={selected.has(p.id)}
                          onChange={() => toggle(p.id)}
                        />
                      </Td>
                    )}
                    <Td>
                      <ShopLink href={`/products/${p.id}`} className="font-medium text-ink hover:text-aqua">
                        {p.name}
                      </ShopLink>
                      <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-faint">
                        <span className="font-mono">{p.sku}</span>
                        {p.urduName && <span className="urdu text-[13px] leading-none">{p.urduName}</span>}
                        {p.formula && <span>{p.formula}</span>}
                        {p.kind !== "STANDARD" && <Badge tone="info">{label(p.kind)}</Badge>}
                      </div>
                    </Td>
                    <Td className="text-muted">{p.categoryName ?? "—"}</Td>
                    <Td className="font-mono text-xs text-muted">{p.barcode ?? "—"}</Td>
                    {showCost && <Td align="right" className="text-muted">{money(p.costAvg, false)}</Td>}
                    <Td align="right">{money(p.price, false)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pager page={page} size={SIZE} total={products.data.total} onPage={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
