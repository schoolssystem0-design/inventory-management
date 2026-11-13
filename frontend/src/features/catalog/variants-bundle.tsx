"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ProductPicker } from "@/components/product-picker";
import { Button } from "@/components/ui/button";
import { Field, Input, Select } from "@/components/ui/fields";
import { Empty, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { money, qty } from "@/lib/format";
import { addMoney, isQty, lineAmount, ZERO } from "@/lib/money";
import { P, ShopLink, useCan } from "@/lib/shop-context";

const words = (s: string) => s.split(/[,\n]+/).map((x) => x.trim()).filter(Boolean);

/** Sizes × colours grid of a design (CAT-04): each cell is its own product with barcode and stock. */
export function VariantGridPanel({ productId }: { productId: number }) {
  const can = useCan();
  const client = useQueryClient();
  const grid = useQuery({ queryKey: ["variants", productId], queryFn: () => api<Schemas["VariantGrid"]>(`/products/${productId}/variants`) });
  const sets = useQuery({ queryKey: ["size-sets"], queryFn: () => api<Schemas["SizeSetView"][]>("/size-sets") });
  const [sizes, setSizes] = React.useState("");
  const [colours, setColours] = React.useState("");
  const [shades, setShades] = React.useState("");
  const create = useMutation({
    mutationFn: () => api<Schemas["VariantGrid"]>(`/products/${productId}/variants`, { method: "POST", body: { sizes: words(sizes), colours: words(colours), shades: words(shades) } }),
    onSuccess: (g) => {
      client.setQueryData(["variants", productId], g);
      client.invalidateQueries({ queryKey: ["product", productId] });
      setSizes("");
      setColours("");
      setShades("");
      toast.success("Variants added");
    },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : errorMessage(e)),
  });
  if (grid.isPending) return <Skeleton className="h-40" />;
  const g = grid.data;
  const cell = (size: string | null, colour: string | null) => g?.variants.find((v) => (v.size ?? null) === size && (v.colour ?? null) === colour);
  const rows = g?.sizes.length ? g.sizes : [null];
  const cols = g?.colours.length ? g.colours : [null];
  return (
    <div className="space-y-4">
      <Panel padded={false} title="Variants" description="Each size / colour is its own item with its own barcode and stock.">
        {!g || g.variants.length === 0 ? (
          <Empty title="No variants yet" description="Add the sizes and colours this design comes in." />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>{g.sizes.length ? "Size" : ""}</Th>
                {cols.map((c) => (
                  <Th key={c ?? "-"} align="right">
                    {c ?? "Stock · price"}
                  </Th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s ?? "-"}>
                  <Td className="font-medium">{s ?? "—"}</Td>
                  {cols.map((c) => {
                    const v = cell(s, c);
                    return (
                      <Td key={c ?? "-"} align="right">
                        {v ? (
                          <ShopLink href={`/products/${v.productId}`} className="hover:text-aqua">
                            <span className={v.stock?.startsWith("-") || v.stock === "0" ? "text-bad" : undefined}>{qty(v.stock)}</span>
                            <span className="block text-xs text-faint">{money(v.price, false)}</span>
                          </ShopLink>
                        ) : (
                          <span className="text-faint">—</span>
                        )}
                      </Td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      {can(P.CATALOG_MANAGE) && (
        <Panel title="Add sizes, colours or shades" description="Separate with commas. Existing combinations are kept.">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Sizes">
              <Input value={sizes} onChange={(e) => setSizes(e.target.value)} placeholder="S, M, L, XL" />
            </Field>
            <Field label="Colours">
              <Input value={colours} onChange={(e) => setColours(e.target.value)} placeholder="Black, White" />
            </Field>
            <Field label="Shades">
              <Input value={shades} onChange={(e) => setShades(e.target.value)} placeholder="For paint and cosmetics" />
            </Field>
          </div>
          <div className="mt-4 flex flex-wrap items-center gap-2">
            {(sets.data?.length ?? 0) > 0 && (
              <Select
                value=""
                onChange={(e) => {
                  const set = sets.data?.find((x) => String(x.id) === e.target.value);
                  if (set) setSizes(set.sizes.join(", "));
                }}
                className="w-auto"
                aria-label="Use a size set"
              >
                <option value="">Use a size set…</option>
                {sets.data?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}: {s.sizes.join(", ")}
                  </option>
                ))}
              </Select>
            )}
            <Button onClick={() => create.mutate()} loading={create.isPending} disabled={!sizes.trim() && !colours.trim() && !shades.trim()}>
              Add variants
            </Button>
          </div>
        </Panel>
      )}
    </div>
  );
}

type Item = { productId: number; name: string; sku: string; qty: string; price: string };

/** Bundle / set (CAT-05): items sold together at one price; stock comes off each item. */
export function BundlePanel({ productId }: { productId: number }) {
  const can = useCan();
  const client = useQueryClient();
  const bundle = useQuery({ queryKey: ["bundle", productId], queryFn: () => api<Schemas["BundleView"]>(`/products/${productId}/bundle`) });
  const [items, setItems] = React.useState<Item[] | null>(null);
  const list = items ?? bundle.data?.items ?? [];
  const save = useMutation({
    mutationFn: () => api<Schemas["BundleView"]>(`/products/${productId}/bundle`, { method: "PUT", body: { items: list.map((i) => ({ productId: i.productId, qty: i.qty })) } }),
    onSuccess: (b) => {
      client.setQueryData(["bundle", productId], b);
      setItems(null);
      toast.success("Bundle saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (bundle.isPending) return <Skeleton className="h-40" />;
  const total = list.reduce((s, i) => (isQty(i.qty) ? addMoney(s, lineAmount(i.qty, i.price)) : s), ZERO);
  const edit = can(P.CATALOG_MANAGE);
  return (
    <Panel
      title="Items in this bundle"
      description={bundle.data ? `Sold for ${money(bundle.data.price)}; items bought separately cost ${money(total)}.` : undefined}
      actions={
        edit &&
        items && (
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={list.some((i) => !isQty(i.qty))}>
            Save
          </Button>
        )
      }
    >
      {edit && (
        <div className="mb-4">
          <ProductPicker
            kinds={["STANDARD", "BATCH"]}
            placeholder="Add an item to the bundle"
            onPick={(p) => {
              if (p.id === productId || list.some((i) => i.productId === p.id)) return;
              setItems([...list, { productId: p.id, name: p.name, sku: p.sku, qty: "1", price: p.price }]);
            }}
          />
        </div>
      )}
      {list.length === 0 ? (
        <Empty title="No items yet" />
      ) : (
        <Table className="min-w-0">
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Qty</Th>
              <Th align="right">Price each</Th>
              <Th />
            </tr>
          </thead>
          <tbody>
            {list.map((i) => (
              <tr key={i.productId}>
                <Td>
                  {i.name}
                  <span className="block text-xs text-faint">{i.sku}</span>
                </Td>
                <Td align="right">
                  {edit ? (
                    <Input
                      value={i.qty}
                      onChange={(e) => setItems(list.map((x) => (x.productId === i.productId ? { ...x, qty: e.target.value } : x)))}
                      className="ml-auto w-20 text-right font-mono"
                      aria-label={`Quantity of ${i.name}`}
                    />
                  ) : (
                    qty(i.qty)
                  )}
                </Td>
                <Td align="right">{money(i.price, false)}</Td>
                <Td>
                  {edit && (
                    <button className="text-faint hover:text-bad" onClick={() => setItems(list.filter((x) => x.productId !== i.productId))} aria-label={`Remove ${i.name}`}>
                      <Trash2 className="size-4" />
                    </button>
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}
