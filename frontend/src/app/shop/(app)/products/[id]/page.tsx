"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Barcode, Power } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { Button, LinkButton } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/dialog";
import { Badge, DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Tabs, Td, Th } from "@/components/ui/layout";
import { ProductForm } from "@/features/catalog/product-form";
import { BundlePanel, VariantGridPanel } from "@/features/catalog/variants-bundle";
import { api, errorMessage, fileUrl, type Schemas } from "@/lib/api/client";
import { dateTime, label, money, qty } from "@/lib/format";
import { P, useCan, useShopHref } from "@/lib/shop-context";

export default function ProductPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const client = useQueryClient();
  const [tab, setTab] = React.useState<"details" | "edit" | "history" | "variants" | "bundle">("details");
  const [confirm, setConfirm] = React.useState(false);

  const product = useQuery({ queryKey: ["product", Number(id)], queryFn: () => api<Schemas["ProductView"]>(`/products/${id}`) });
  const history = useQuery({
    queryKey: ["product-history", Number(id)],
    queryFn: () => api<Schemas["HistoryEntry"][]>(`/products/${id}/history`),
    enabled: tab === "history",
  });

  const toggle = useMutation({
    mutationFn: (active: boolean) => api<Schemas["ProductView"]>(`/products/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" }),
    onSuccess: (p) => {
      client.setQueryData(["product", p.id], p);
      client.invalidateQueries({ queryKey: ["products"] });
      setConfirm(false);
      toast.success(p.active ? "Product activated" : "Product deactivated");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const generate = useMutation({
    mutationFn: () => api<Schemas["ProductView"]>(`/products/${id}/generate-barcode`, { method: "POST" }),
    onSuccess: (p) => {
      client.setQueryData(["product", p.id], p);
      toast.success(`Barcode ${p.barcodes[0]} created`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (product.isPending) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-72" />
      </div>
    );
  }
  if (product.isError) return <ErrorState message={errorMessage(product.error)} onRetry={() => product.refetch()} />;
  const p = product.data;

  return (
    <>
      <PageHeader
        back={{ href: toHref("/products"), label: "Products" }}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {p.name}
            {!p.active && <Badge tone="bad">Deactivated</Badge>}
          </span>
        }
        description={
          <span className="font-mono">
            {p.sku}
            {p.urduName && <span className="urdu ml-3 font-sans">{p.urduName}</span>}
          </span>
        }
        actions={
          <>
            {can(P.LABELS_PRINT) && (
              <LinkButton href={toHref(`/products/labels?ids=${p.id}`)} variant="secondary">
                <Barcode /> Labels
              </LinkButton>
            )}
            {can(P.CATALOG_MANAGE) && (
              <Button variant={p.active ? "danger" : "secondary"} onClick={() => (p.active ? setConfirm(true) : toggle.mutate(true))}>
                <Power /> {p.active ? "Deactivate" : "Activate"}
              </Button>
            )}
          </>
        }
      />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "details", label: "Details" },
          { value: "variants", label: "Sizes and colours", hidden: p.kind !== "VARIANT_PARENT" },
          { value: "bundle", label: p.kind === "RECIPE" ? "Recipe" : "Bundle items", hidden: p.kind !== "BUNDLE" && p.kind !== "RECIPE" },
          { value: "edit", label: "Edit", hidden: !can(P.CATALOG_MANAGE) },
          { value: "history", label: "History" },
        ]}
      />

      {tab === "variants" && <VariantGridPanel productId={p.id} />}
      {tab === "bundle" && <BundlePanel productId={p.id} recipe={p.kind === "RECIPE"} />}
      {tab === "details" && (
        <div className="grid gap-5 lg:grid-cols-3">
          <div className="space-y-5 lg:col-span-2">
            <Panel title="Product">
              <DetailList
                items={[
                  { label: "Category", value: p.categoryName },
                  { label: "Brand / company", value: p.brandName },
                  { label: "Unit", value: p.unitName },
                  { label: "Tracked as", value: label(p.kind) },
                  { label: "Sale price", value: <span className="font-mono">{money(p.price)}</span> },
                  { label: "Wholesale price", value: <span className="font-mono">{money(p.wholesalePrice)}</span> },
                  { label: "Lowest price allowed", value: <span className="font-mono">{money(p.minPrice)}</span> },
                  ...(can(P.COST_VIEW) ? [{ label: "Average cost", value: <span className="font-mono">{money(p.costAvg)}</span> }] : []),
                  { label: "Tax rate", value: p.taxRate ? `${p.taxRate}%` : "Shop rate" },
                  { label: "Reorder level", value: qty(p.reorderLevel) },
                  ...(p.weighed ? [{ label: "Sold by weight", value: "Yes" }] : []),
                ]}
              />
              {p.description && <p className="mt-4 text-sm whitespace-pre-line text-muted">{p.description}</p>}
            </Panel>
            {p.pharma && (
              <Panel title="Medicine details">
                <DetailList
                  items={[
                    { label: "Formula", value: p.pharma.formula },
                    { label: "Strength", value: p.pharma.strength },
                    { label: "Form", value: p.pharma.dosageForm },
                    { label: "Manufacturer", value: p.pharma.company },
                    { label: "Pack size", value: p.pharma.packSize },
                    { label: "Schedule", value: p.pharma.schedule },
                    { label: "Rack", value: p.pharma.rack },
                    { label: "Registration no.", value: p.pharma.registrationNo },
                  ]}
                />
              </Panel>
            )}
            {p.part && (
              <Panel title="Part numbers">
                <DetailList
                  items={[
                    { label: "OEM number", value: p.part.oemNumber },
                    { label: "Alternates", value: (p.part.alternateNumbers ?? []).join(", ") || "—" },
                  ]}
                />
              </Panel>
            )}
            {p.book && (
              <Panel title="Book details">
                <DetailList
                  items={[
                    { label: "ISBN", value: p.book.isbn },
                    { label: "Author", value: p.book.author },
                    { label: "Publisher", value: p.book.publisher },
                    { label: "Edition", value: p.book.edition },
                    { label: "Class", value: p.book.className },
                  ]}
                />
              </Panel>
            )}
            {p.packUnits.length > 0 && (
              <Panel title="Pack sizes" padded={false}>
                <Table>
                  <thead>
                    <tr>
                      <Th>Unit</Th>
                      <Th align="right">Contains</Th>
                      <Th align="right">Price</Th>
                      <Th>Barcode</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {p.packUnits.map((u) => (
                      <tr key={u.unitId}>
                        <Td>{u.unitName}</Td>
                        <Td align="right">
                          {qty(u.factor)} {p.unitName}
                        </Td>
                        <Td align="right">{money(u.price, false)}</Td>
                        <Td className="font-mono text-xs">{u.barcode ?? "—"}</Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              </Panel>
            )}
          </div>
          <div className="space-y-5">
            {p.imageFileId && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={fileUrl(p.imageFileId) ?? ""} alt={p.name} className="glass aspect-square w-full object-cover" />
            )}
            <Panel title="Barcodes">
              {p.barcodes.length === 0 ? (
                <div className="space-y-3 text-sm text-muted">
                  <p>No barcode yet.</p>
                  {can(P.CATALOG_MANAGE) && (
                    <Button variant="secondary" onClick={() => generate.mutate()} loading={generate.isPending}>
                      <Barcode /> Create an in-store barcode
                    </Button>
                  )}
                </div>
              ) : (
                <ul className="space-y-1 font-mono text-sm">
                  {p.barcodes.map((b) => (
                    <li key={b}>{b}</li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title="Record">
              <DetailList
                items={[
                  { label: "Added", value: dateTime(p.createdAt) },
                  { label: "Last changed", value: dateTime(p.updatedAt) },
                ]}
              />
            </Panel>
          </div>
        </div>
      )}

      {tab === "edit" && (
        <ProductForm
          key={p.version}
          product={p}
          onSaved={(saved) => {
            client.setQueryData(["product", saved.id], saved);
            client.invalidateQueries({ queryKey: ["product-history", saved.id] });
            setTab("details");
          }}
        />
      )}

      {tab === "history" && (
        <Panel padded={false}>
          {history.isPending ? (
            <div className="p-5">
              <Skeleton className="h-32" />
            </div>
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>When</Th>
                  <Th>What</Th>
                  <Th>Who</Th>
                  <Th align="right">Price</Th>
                  {can(P.COST_VIEW) && <Th align="right">Cost</Th>}
                </tr>
              </thead>
              <tbody>
                {(history.data ?? []).map((h, i) => (
                  <tr key={i}>
                    <Td className="whitespace-nowrap text-muted">{dateTime(h.at)}</Td>
                    <Td>{h.description}</Td>
                    <Td className="text-muted">{h.actorName ?? "—"}</Td>
                    <Td align="right">{h.newPrice ? `${h.oldPrice ? money(h.oldPrice, false) + " → " : ""}${money(h.newPrice, false)}` : ""}</Td>
                    {can(P.COST_VIEW) && (
                      <Td align="right">{h.newCost ? `${h.oldCost ? money(h.oldCost, false) + " → " : ""}${money(h.newCost, false)}` : ""}</Td>
                    )}
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
          <p className="px-4 py-3 text-xs text-faint">Stock movements appear here once buying and selling start (Stage 3).</p>
        </Panel>
      )}

      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Deactivate ${p.name}?`}
        description="It disappears from lists and the till. Its history is kept and you can activate it again."
        confirmLabel="Deactivate"
        danger
        loading={toggle.isPending}
        onConfirm={() => toggle.mutate(false)}
      />
    </>
  );
}
