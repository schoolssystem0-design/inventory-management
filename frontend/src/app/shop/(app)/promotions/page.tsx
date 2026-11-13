"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, X } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ProductPicker } from "@/components/product-picker";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Checkbox, Field, fieldError, Input, Select, Switch } from "@/components/ui/fields";
import { Badge, Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { P, useCan } from "@/lib/shop-context";

type Offer = Schemas["PromotionView"];
const KINDS = ["PERCENT", "AMOUNT", "BUY_GET", "MULTI_PRICE", "BILL_PERCENT", "BILL_AMOUNT"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** Offers and coupons (SUP-05, XSL-02). The till applies them by itself; cashiers cannot type them. */
export default function PromotionsPage() {
  const can = useCan();
  const [editing, setEditing] = React.useState<Offer | "new" | null>(null);
  const list = useQuery({ queryKey: ["promotions"], queryFn: () => api<Offer[]>("/promotions") });
  return (
    <>
      <PageHeader
        title="Offers and coupons"
        description="Offers apply at the till by themselves. A coupon offer applies only when its code is typed in at the till."
        actions={
          can(P.PROMOTIONS_MANAGE) && (
            <Button onClick={() => setEditing("new")}>
              <Plus /> New offer
            </Button>
          )
        }
      />
      <Panel padded={false}>
        {list.isPending ? (
          <TableSkeleton />
        ) : list.isError ? (
          <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <Empty title="No offers yet" description="Buy 2 get 1 free, 10% off a category, happy hour, Rs 100 off bills over Rs 2,000, or a coupon code." />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Offer</Th>
                <Th>What it gives</Th>
                <Th>When</Th>
                <Th>Coupon</Th>
                <Th align="right">Given so far</Th>
                <Th>Now</Th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((o) => (
                <tr key={o.id} className={o.active ? "" : "opacity-60"}>
                  <Td>
                    <button className="text-left font-medium hover:text-aqua disabled:hover:text-inherit" onClick={() => setEditing(o)} disabled={!can(P.PROMOTIONS_MANAGE)}>
                      {o.name}
                    </button>
                    <span className="block text-xs text-faint">{label(o.kind)}</span>
                  </Td>
                  <Td>{o.summary}</Td>
                  <Td className="text-muted">{when(o)}</Td>
                  <Td>
                    {o.couponCode ? (
                      <>
                        <span className="font-mono">{o.couponCode}</span>
                        <span className="block text-xs text-faint">
                          used {o.uses}
                          {o.maxUses ? ` of ${o.maxUses}` : ""}
                        </span>
                      </>
                    ) : (
                      "—"
                    )}
                  </Td>
                  <Td align="right">{money(o.given)}</Td>
                  <Td>{!o.active ? <Badge>Off</Badge> : o.runningNow ? <Badge tone="good">Running</Badge> : <Badge tone="neutral">Not now</Badge>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      {editing && <OfferDialog offer={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function when(o: Offer): string {
  const parts: string[] = [];
  if (o.startsOn || o.endsOn) parts.push(`${o.startsOn ? date(o.startsOn) : "now"} – ${o.endsOn ? date(o.endsOn) : "no end"}`);
  if (o.days?.length) parts.push(o.days.map((d) => DAYS[d - 1]).join(", "));
  if (o.fromTime && o.toTime) parts.push(`${o.fromTime.slice(0, 5)}–${o.toTime.slice(0, 5)}`);
  return parts.join(" · ") || "Always";
}

type Category = { id: number; name: string };

function OfferDialog({ offer, onClose }: { offer: Offer | null; onClose: () => void }) {
  const client = useQueryClient();
  const [form, setForm] = React.useState({
    name: offer?.name ?? "",
    kind: offer?.kind ?? "PERCENT",
    value: offer?.value && offer.value !== "0.00" ? offer.value : "",
    buyQty: offer?.buyQty ? String(Number(offer.buyQty)) : "2",
    getQty: offer?.getQty ? String(Number(offer.getQty)) : "1",
    minAmount: offer?.minAmount && offer.minAmount !== "0.00" ? offer.minAmount : "",
    scope: offer?.scope ?? "ALL",
    categoryIds: offer?.categoryIds ?? [],
    products: (offer?.productIds ?? []).map((id) => ({ id, name: `#${id}` })),
    startsOn: offer?.startsOn ?? "",
    endsOn: offer?.endsOn ?? "",
    days: offer?.days ?? [],
    fromTime: offer?.fromTime?.slice(0, 5) ?? "",
    toTime: offer?.toTime?.slice(0, 5) ?? "",
    couponCode: offer?.couponCode ?? "",
    maxUses: offer?.maxUses ? String(offer.maxUses) : "",
    active: offer?.active ?? true,
  });
  const set = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) => setForm((f) => ({ ...f, [key]: value }));
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Category[]>("/categories") });
  // Names of products already on an offer being edited.
  useQuery({
    queryKey: ["offer-products", offer?.id],
    enabled: !!offer?.productIds?.length,
    queryFn: async () => {
      const named = await Promise.all((offer?.productIds ?? []).map((id) => api<{ id: number; name: string }>(`/products/${id}`).catch(() => ({ id, name: `#${id}` }))));
      setForm((f) => ({ ...f, products: named.map((p) => ({ id: p.id, name: p.name })) }));
      return named;
    },
  });
  const bill = form.kind.startsWith("BILL_");
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: form.name,
        kind: form.kind,
        value: form.kind === "BUY_GET" ? null : form.value || null,
        buyQty: form.kind === "BUY_GET" || form.kind === "MULTI_PRICE" ? form.buyQty : null,
        getQty: form.kind === "BUY_GET" ? form.getQty : null,
        minAmount: bill ? form.minAmount || null : null,
        scope: bill ? "ALL" : form.scope,
        categoryIds: form.scope === "CATEGORY" ? form.categoryIds : [],
        productIds: form.scope === "PRODUCTS" ? form.products.map((p) => p.id) : [],
        startsOn: form.startsOn || null,
        endsOn: form.endsOn || null,
        days: form.days,
        fromTime: form.fromTime || null,
        toTime: form.toTime || null,
        couponCode: form.couponCode || null,
        maxUses: form.maxUses ? Number(form.maxUses) : null,
        active: form.active,
      };
      return offer ? api<Offer>(`/promotions/${offer.id}`, { method: "PUT", body }) : api<Offer>("/promotions", { method: "POST", body });
    },
    onSuccess: (o) => {
      toast.success(`${o.name} saved`);
      client.invalidateQueries({ queryKey: ["promotions"] });
      onClose();
    },
  });
  const err = (name: string) => fieldError(save.error, name);
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={offer ? `Change ${offer.name}` : "New offer"}
      wide
      footer={
        <Button type="submit" form="offer-form" loading={save.isPending} disabled={!form.name.trim()}>
          Save offer
        </Button>
      }
    >
      <form
        id="offer-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name" required error={err("name")}>
            <Input value={form.name} onChange={(e) => set("name", e.target.value)} autoFocus placeholder="Eid sale" />
          </Field>
          <Field label="Kind of offer" error={err("kind")}>
            <Select value={form.kind} onChange={(e) => set("kind", e.target.value)}>
              {KINDS.map((k) => (
                <option key={k} value={k}>
                  {label(k)}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          {(form.kind === "BUY_GET" || form.kind === "MULTI_PRICE") && (
            <Field label={form.kind === "BUY_GET" ? "Buy" : "How many"} error={err("buyQty")}>
              <Input value={form.buyQty} onChange={(e) => set("buyQty", e.target.value)} inputMode="numeric" />
            </Field>
          )}
          {form.kind === "BUY_GET" && (
            <Field label="Get free" error={err("getQty")}>
              <Input value={form.getQty} onChange={(e) => set("getQty", e.target.value)} inputMode="numeric" />
            </Field>
          )}
          {form.kind !== "BUY_GET" && (
            <Field
              label={form.kind.endsWith("PERCENT") ? "Percent off" : form.kind === "MULTI_PRICE" ? "Price for them together (Rs)" : form.kind === "AMOUNT" ? "Rs off each" : "Rs off the bill"}
              error={err("value")}
            >
              <Input value={form.value} onChange={(e) => set("value", e.target.value)} inputMode="decimal" />
            </Field>
          )}
          {bill && (
            <Field label="Bill at least (Rs)" error={err("minAmount")}>
              <Input value={form.minAmount} onChange={(e) => set("minAmount", e.target.value)} inputMode="decimal" placeholder="Any bill" />
            </Field>
          )}
        </div>
        {!bill && (
          <Field label="Applies to" error={err("categoryIds") ?? err("productIds")}>
            <Select value={form.scope} onChange={(e) => set("scope", e.target.value)}>
              <option value="ALL">Every item</option>
              <option value="CATEGORY">Items in categories</option>
              <option value="PRODUCTS">Chosen products</option>
            </Select>
          </Field>
        )}
        {!bill && form.scope === "CATEGORY" && (
          <div className="flex flex-wrap gap-2 rounded-xl border border-line p-3">
            {(categories.data ?? []).map((c) => (
              <Checkbox
                key={c.id}
                label={c.name}
                checked={form.categoryIds.includes(c.id)}
                onChange={(e) => set("categoryIds", e.target.checked ? [...form.categoryIds, c.id] : form.categoryIds.filter((x) => x !== c.id))}
              />
            ))}
            {categories.data?.length === 0 && <p className="text-sm text-muted">No categories yet.</p>}
          </div>
        )}
        {!bill && form.scope === "PRODUCTS" && (
          <div className="space-y-2">
            <ProductPicker
              placeholder="Add a product to the offer"
              onPick={(p) => !form.products.some((x) => x.id === p.id) && set("products", [...form.products, { id: p.id, name: p.name }])}
            />
            <div className="flex flex-wrap gap-2">
              {form.products.map((p) => (
                <span key={p.id} className="inline-flex items-center gap-1 rounded-full bg-raised px-3 py-1 text-sm">
                  {p.name}
                  <button type="button" aria-label={`Remove ${p.name}`} onClick={() => set("products", form.products.filter((x) => x.id !== p.id))}>
                    <X className="size-3.5" />
                  </button>
                </span>
              ))}
            </div>
          </div>
        )}
        <fieldset className="space-y-3 rounded-xl border border-line p-3">
          <legend className="px-1 text-sm font-medium">When it runs</legend>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="From date" error={err("startsOn")}>
              <Input type="date" value={form.startsOn} onChange={(e) => set("startsOn", e.target.value)} />
            </Field>
            <Field label="Until date" error={err("endsOn")}>
              <Input type="date" value={form.endsOn} onChange={(e) => set("endsOn", e.target.value)} />
            </Field>
          </div>
          <div className="flex flex-wrap gap-2" role="group" aria-label="Days of the week">
            {DAYS.map((d, i) => (
              <Checkbox key={d} label={d} checked={form.days.includes(i + 1)} onChange={(e) => set("days", e.target.checked ? [...form.days, i + 1] : form.days.filter((x) => x !== i + 1))} />
            ))}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="From time (happy hour)" error={err("fromTime")}>
              <Input type="time" value={form.fromTime} onChange={(e) => set("fromTime", e.target.value)} />
            </Field>
            <Field label="Until time" error={err("toTime")}>
              <Input type="time" value={form.toTime} onChange={(e) => set("toTime", e.target.value)} />
            </Field>
          </div>
          <p className="text-xs text-muted">Leave dates, days and times empty to run all the time.</p>
        </fieldset>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Coupon code" error={err("couponCode")} hint="Only with this code at the till. Leave empty for an automatic offer.">
            <Input value={form.couponCode} onChange={(e) => set("couponCode", e.target.value.toUpperCase())} className="font-mono" placeholder="EID20" />
          </Field>
          <Field label="Can be used (times)" error={err("maxUses")} hint="Empty = no limit.">
            <Input value={form.maxUses} onChange={(e) => set("maxUses", e.target.value)} inputMode="numeric" />
          </Field>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <Switch label="Offer is on" checked={form.active} onChange={(v) => set("active", v)} />
          {form.active ? "Offer is on" : "Offer is off"}
        </div>
        {save.error && !err("name") && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}
