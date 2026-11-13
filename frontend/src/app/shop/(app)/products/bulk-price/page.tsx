"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/dialog";
import { AmountInput, Checkbox, Field, Input, Select } from "@/components/ui/fields";
import { Empty, PageHeader, Panel, Table, Td, Th } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { money, plural } from "@/lib/format";
import { useShopHref } from "@/lib/shop-context";

type Request = Schemas["BulkPriceRequest"];

/** CAT-05: change prices by %, by an amount or to a fixed price for a category, brand or all products, with a preview. */
export default function BulkPricePage() {
  const toHref = useShopHref();
  const client = useQueryClient();
  const [categoryId, setCategoryId] = React.useState("");
  const [brandId, setBrandId] = React.useState("");
  const [all, setAll] = React.useState(false);
  const [field, setField] = React.useState("PRICE");
  const [mode, setMode] = React.useState("PERCENT");
  const [value, setValue] = React.useState("");
  const [rounding, setRounding] = React.useState("NONE");
  const [reason, setReason] = React.useState("");
  const [confirm, setConfirm] = React.useState(false);

  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Schemas["CategoryView"][]>("/categories") });
  const brands = useQuery({ queryKey: ["brands"], queryFn: () => api<Schemas["BrandView"][]>("/brands") });

  const request: Request = {
    categoryId: categoryId ? Number(categoryId) : undefined,
    brandId: brandId ? Number(brandId) : undefined,
    allProducts: all,
    field,
    mode,
    value,
    rounding,
    reason,
  };
  const preview = useMutation({
    mutationFn: () => api<Schemas["BulkPriceResult"]>("/products/bulk-price/preview", { method: "POST", body: request }),
    onError: (e) => toast.error(errorMessage(e)),
  });
  const apply = useMutation({
    mutationFn: () => api<Schemas["BulkPriceResult"]>("/products/bulk-price", { method: "POST", body: request }),
    onSuccess: (r) => {
      setConfirm(false);
      preview.reset();
      client.invalidateQueries({ queryKey: ["products"] });
      toast.success(`${plural(r.count, "price")} changed`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const valueError = preview.error instanceof ApiError ? preview.error.field("value") : undefined;

  return (
    <>
      <PageHeader title="Change prices" description="Raise or lower many prices at once" back={{ href: toHref("/products"), label: "Products" }} />
      <div className="grid gap-5 lg:grid-cols-3">
        <Panel title="Which products">
          <div className="space-y-4">
            <Field label="Category">
              <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} disabled={all}>
                <option value="">Any category</option>
                {categories.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Brand">
              <Select value={brandId} onChange={(e) => setBrandId(e.target.value)} disabled={all}>
                <option value="">Any brand</option>
                {brands.data?.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Checkbox label="All active products" checked={all} onChange={(e) => setAll(e.target.checked)} />
          </div>
        </Panel>
        <Panel title="How" className="lg:col-span-2">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Price to change">
              <Select value={field} onChange={(e) => setField(e.target.value)}>
                <option value="PRICE">Sale price</option>
                <option value="WHOLESALE">Wholesale price</option>
              </Select>
            </Field>
            <Field label="Change by">
              <Select value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="PERCENT">Percentage (e.g. 5 or -10)</option>
                <option value="AMOUNT">Amount in rupees (e.g. 20 or -15)</option>
                <option value="SET">Set every price to</option>
              </Select>
            </Field>
            <Field label={mode === "PERCENT" ? "Percent" : "Rupees"} error={valueError}>
              <AmountInput value={value} onChange={(e) => setValue(e.target.value)} inputMode="text" />
            </Field>
            <Field label="Round new prices to">
              <Select value={rounding} onChange={(e) => setRounding(e.target.value)}>
                <option value="NONE">No rounding</option>
                <option value="ONE">Nearest 1</option>
                <option value="FIVE">Nearest 5</option>
                <option value="TEN">Nearest 10</option>
              </Select>
            </Field>
            <Field label="Reason" hint="Shown in each product's price history" className="sm:col-span-2">
              <Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="New rate list from the company" />
            </Field>
          </div>
          <Button className="mt-5" onClick={() => preview.mutate()} loading={preview.isPending} disabled={!value.trim()}>
            Preview changes
          </Button>
        </Panel>
      </div>

      {preview.data && (
        <Panel
          className="mt-5"
          padded={false}
          title={`${plural(preview.data.count, "price")} will change`}
          actions={
            preview.data.count > 0 && (
              <Button onClick={() => setConfirm(true)}>Apply {plural(preview.data.count, "change")}</Button>
            )
          }
        >
          {preview.data.count === 0 ? (
            <Empty title="Nothing would change" description="No product matches, or the prices are already the same." />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Product</Th>
                  <Th align="right">Now</Th>
                  <Th align="right">New</Th>
                </tr>
              </thead>
              <tbody>
                {preview.data.lines.map((l) => (
                  <tr key={l.id}>
                    <Td>
                      {l.name}
                      <span className="ml-2 font-mono text-xs text-faint">{l.sku}</span>
                    </Td>
                    <Td align="right" className="text-muted">
                      {money(l.oldPrice, false)}
                    </Td>
                    <Td align="right" className="text-aqua">
                      {money(l.newPrice, false)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>
      )}

      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title="Apply the new prices?"
        description={`${plural(preview.data?.count ?? 0, "price")} will change. Each change is recorded in the product's history.`}
        confirmLabel="Apply"
        loading={apply.isPending}
        onConfirm={() => apply.mutate()}
      />
    </>
  );
}
