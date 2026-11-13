"use client";

import { useMutation } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ProductPicker } from "@/components/product-picker";
import { Button } from "@/components/ui/button";
import { Field, fieldError, Input, Textarea } from "@/components/ui/fields";
import { Empty, PageHeader, Panel, Table, Td, Th } from "@/components/ui/layout";
import { PartyPicker, type PickedParty } from "@/features/parties/customer-picker";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { addMoney, isMoney, isQty, lineAmount, ZERO } from "@/lib/money";
import { P, useBranch, useCan, useShopHref, useShopRouter } from "@/lib/shop-context";

type Line = { productId: number; name: string; unitName: string; qty: string; price: string };

/** A new delivery challan: customer, address and the goods sent. Stock leaves when it is saved. */
export default function NewChallanPage() {
  const can = useCan();
  const canPrice = can(P.POS_PRICE_OVERRIDE);
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const [customer, setCustomer] = React.useState<PickedParty | null>(null);
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  const [address, setAddress] = React.useState("");
  const [note, setNote] = React.useState("");
  const [lines, setLines] = React.useState<Line[]>([]);
  const total = lines.reduce((s, l) => (isQty(l.qty) && isMoney(l.price) ? addMoney(s, lineAmount(l.qty, l.price)) : s), ZERO);
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["ChallanView"]>("/challans", {
        method: "POST",
        body: {
          branchId: branch?.id ?? null,
          customerId: customer?.id ?? null,
          customerName: customer ? null : name,
          customerPhone: phone || null,
          address: address || null,
          note: note || null,
          lines: lines.map((l) => ({ productId: l.productId, qty: l.qty, price: l.price })),
        },
      }),
    onSuccess: (c) => {
      toast.success(`Challan ${c.number} saved — stock sent out`);
      router.push(`/challans/${c.id}`);
    },
  });
  const valid = lines.length > 0 && lines.every((l) => isQty(l.qty) && Number(l.qty) > 0 && isMoney(l.price)) && (customer || name.trim());
  return (
    <>
      <PageHeader back={{ href: toHref("/challans"), label: "Delivery challans" }} title="New challan" description={branch ? `Goods leave ${branch.name}` : undefined} />
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Panel title="Goods" padded={false}>
          <div className="border-b border-line p-4">
            <ProductPicker
              autoFocus
              kinds={["STANDARD", "BATCH", "SERVICE"]}
              onPick={(p: Schemas["ProductListItem"]) =>
                setLines((ls) => (ls.some((l) => l.productId === p.id) ? ls.map((l) => (l.productId === p.id ? { ...l, qty: String(Number(l.qty) + 1) } : l)) : [...ls, { productId: p.id, name: p.name, unitName: p.unitName, qty: "1", price: p.price }]))
              }
            />
          </div>
          {lines.length === 0 ? (
            <Empty title="Scan or search the goods being sent" />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Item</Th>
                  <Th align="right">Qty</Th>
                  <Th align="right">Price</Th>
                  <Th align="right">Amount</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={l.productId}>
                    <Td>
                      {l.name} <span className="text-xs text-faint">{l.unitName}</span>
                    </Td>
                    <Td align="right">
                      <Input aria-label={`Quantity of ${l.name}`} value={l.qty} onChange={(e) => setLines((ls) => ls.map((x, k) => (k === i ? { ...x, qty: e.target.value } : x)))} className="ml-auto h-8 w-20 text-right font-mono" inputMode="decimal" />
                    </Td>
                    <Td align="right">
                      <Input aria-label={`Price of ${l.name}`} value={l.price} onChange={(e) => setLines((ls) => ls.map((x, k) => (k === i ? { ...x, price: e.target.value } : x)))} className="ml-auto h-8 w-28 text-right font-mono" inputMode="decimal" disabled={!canPrice} title={canPrice ? undefined : "Only users who may change prices can set another price"} />
                    </Td>
                    <Td align="right">{isQty(l.qty) && isMoney(l.price) ? money(lineAmount(l.qty, l.price), false) : "—"}</Td>
                    <Td align="right">
                      <Button size="sm" variant="ghost" aria-label={`Remove ${l.name}`} onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>
                        <Trash2 />
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
          <div className="flex justify-end border-t border-line px-5 py-3 text-sm font-semibold">Value {money(total)}</div>
        </Panel>
        <Panel title="Customer">
          <div className="space-y-3">
            <PartyPicker kind="customer" value={customer} onChange={setCustomer} placeholder="Search a saved customer" />
            {!customer && (
              <Field label="Or a name" error={fieldError(save.error, "customerName")}>
                <Input value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
            )}
            <Field label="Phone">
              <Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" />
            </Field>
            <Field label="Deliver to">
              <Textarea value={address} onChange={(e) => setAddress(e.target.value)} rows={2} placeholder="Site address" />
            </Field>
            <Field label="Note">
              <Input value={note} onChange={(e) => setNote(e.target.value)} />
            </Field>
            {save.error && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
            <Button className="w-full" onClick={() => save.mutate()} loading={save.isPending} disabled={!valid}>
              Save challan
            </Button>
            <p className="text-xs text-muted">Prices are what the customer will be billed. Stock leaves the shop now.</p>
          </div>
        </Panel>
      </div>
    </>
  );
}
