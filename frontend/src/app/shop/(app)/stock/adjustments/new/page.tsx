"use client";

import { useMutation } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { Button } from "@/components/ui/button";
import { Field, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { DocLines, serialList, useDocLines } from "@/features/documents/doc-lines";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { P, useBranch, useCan, useShop, useShopHref, useShopRouter } from "@/lib/shop-context";
import { ADJUST_REASONS } from "@/features/documents/reasons";

export default function NewAdjustmentPage() {
  const shop = useShop();
  const can = useCan();
  const toHref = useShopHref();
  const router = useShopRouter();
  const approval = useApproval();
  const [branch] = useBranch();
  const [branchId, setBranchId] = React.useState<number | null>(null);
  const [reason, setReason] = React.useState<string>("DAMAGED");
  const [direction, setDirection] = React.useState<"in" | "out">("out");
  const [note, setNote] = React.useState("");
  const doc = useDocLines();
  const fixed = ADJUST_REASONS.find((r) => r.value === reason)?.out;
  const out = fixed === null || fixed === undefined ? direction === "out" : fixed;
  const where = branchId ?? branch?.id ?? null;
  const save = useMutation({
    mutationFn: () => {
      const body = {
        branchId: where,
        reason,
        note: note || null,
        lines: doc.lines.map((l) => {
          const serial = l.product.kind === "SERIAL";
          const sn = serial ? serialList(l.serials) : [];
          return {
            productId: l.product.id,
            qty: serial ? null : (reason === "OTHER" && out ? "-" : "") + l.qty,
            unitCost: !out && can(P.COST_VIEW) ? l.cost || null : null,
            batchId: out ? l.batchId : null,
            batchNo: !out ? l.batchNo || null : null,
            expiry: !out ? l.expiry || null : null,
            serials: serial && out ? sn : null,
            newSerials: serial && !out ? sn.map((s) => ({ serial: s })) : null,
          };
        }),
      };
      return approval.run((headers) => api<Schemas["AdjustmentView"]>("/adjustments", { method: "POST", body, headers }));
    },
    onSuccess: (a) => {
      toast.success(`Adjustment ${a.number} saved`);
      router.push(`/stock/adjustments/${a.id}`);
    },
    onError: (e) => {
      if (!(e instanceof ApiError && e.code === "approval_required")) toast.error(errorMessage(e));
    },
  });
  return (
    <>
      <PageHeader back={{ href: toHref("/stock/adjustments"), label: "Adjustments" }} title="New stock adjustment" />
      <div className="space-y-4">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Reason">
              <Select value={reason} onChange={(e) => setReason(e.target.value)}>
                {ADJUST_REASONS.map((r) => (
                  <option key={r.value} value={r.value}>
                    {r.label}
                  </option>
                ))}
              </Select>
            </Field>
            {reason === "OTHER" && (
              <Field label="Direction">
                <Select value={direction} onChange={(e) => setDirection(e.target.value as "in" | "out")}>
                  <option value="out">Take out of stock</option>
                  <option value="in">Add to stock</option>
                </Select>
              </Field>
            )}
            {shop.branches.length > 1 && (
              <Field label="Branch">
                <Select value={where ?? ""} onChange={(e) => setBranchId(Number(e.target.value))}>
                  {shop.branches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name}
                    </option>
                  ))}
                </Select>
              </Field>
            )}
          </div>
          <p className="mt-3 text-sm text-muted">{out ? "These items leave stock at their cost." : "These items are added to stock."}</p>
        </Panel>
        <Panel title="Items">
          <DocLines
            key={out ? "out" : "in"}
            lines={doc.lines}
            update={doc.update}
            remove={doc.remove}
            onAdd={doc.add}
            branchId={where}
            config={{
              cost: !out && can(P.COST_VIEW),
              batch: out ? "pick" : "new",
              serials: out ? "existing" : "new",
              kinds: ["STANDARD", "BATCH", "SERIAL"],
            }}
          />
        </Panel>
        <Panel>
          <Field label="Note">
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-10" />
          </Field>
          <div className="mt-4 flex justify-end">
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!doc.lines.length}>
              Save adjustment
            </Button>
          </div>
        </Panel>
      </div>
      {approval.dialog}
    </>
  );
}
