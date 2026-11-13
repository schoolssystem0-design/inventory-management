"use client";

import { useMutation } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Field, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { DocLines, serialList, useDocLines } from "@/features/documents/doc-lines";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { useBranch, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";

export default function NewTransferPage() {
  const shop = useShop();
  const toHref = useShopHref();
  const router = useShopRouter();
  const [branch] = useBranch();
  const [from, setFrom] = React.useState<number | null>(branch?.id ?? null);
  const fromId = from ?? branch?.id ?? null;
  const [to, setTo] = React.useState<number | null>(shop.branches.find((b) => b.id !== fromId)?.id ?? null);
  const [note, setNote] = React.useState("");
  const doc = useDocLines();
  const save = useMutation({
    mutationFn: () =>
      api<Schemas["TransferView"]>("/transfers", {
        method: "POST",
        body: {
          fromBranchId: fromId,
          toBranchId: to,
          note: note || null,
          lines: doc.lines.map((l) => ({
            productId: l.product.id,
            qty: l.product.kind === "SERIAL" ? null : l.qty,
            batchId: l.batchId,
            serials: l.product.kind === "SERIAL" ? serialList(l.serials) : null,
          })),
        },
      }),
    onSuccess: (t) => {
      toast.success(`Transfer ${t.number} sent`);
      router.push(`/stock/transfers/${t.id}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader back={{ href: toHref("/stock/transfers"), label: "Transfers" }} title="Send stock to another branch" />
      <div className="space-y-4">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="From">
              <Select value={fromId ?? ""} onChange={(e) => setFrom(Number(e.target.value))}>
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="To" error={to === fromId ? "Choose a different branch" : undefined}>
              <Select value={to ?? ""} onChange={(e) => setTo(Number(e.target.value))}>
                {shop.branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
        </Panel>
        <Panel title="Items">
          <DocLines lines={doc.lines} update={doc.update} remove={doc.remove} onAdd={doc.add} branchId={fromId} config={{ batch: "pick", serials: "existing", kinds: ["STANDARD", "BATCH", "SERIAL"] }} />
        </Panel>
        <Panel>
          <Field label="Note">
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
          </Field>
          <div className="mt-4 flex justify-end">
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!doc.lines.length || !to || to === fromId}>
              Send
            </Button>
          </div>
        </Panel>
      </div>
    </>
  );
}
