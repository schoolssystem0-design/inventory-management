"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, ShoppingCart } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Select } from "@/components/ui/fields";
import { DetailList, ErrorState, PageHeader, Panel, Skeleton, Table, Td, Th } from "@/components/ui/layout";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { date, money, qty } from "@/lib/format";
import { P, ShopLink, useCan, useShopHref, useShopRouter } from "@/lib/shop-context";

export default function QuotationPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["quotation", id], queryFn: () => api<Schemas["QuotationView"]>(`/quotations/${id}`) });
  const [dialog, setDialog] = React.useState<"convert" | "cancel" | null>(null);
  const cancel = useMutation({
    mutationFn: () => api(`/quotations/${id}/cancel`, { method: "POST" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["quotation", id] });
      setDialog(null);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (q.isPending) return <Skeleton className="h-96" />;
  if (q.isError) return <ErrorState message={errorMessage(q.error)} onRetry={() => q.refetch()} />;
  const v = q.data;
  const open = v.status === "OPEN" && !v.expired;
  return (
    <>
      <PageHeader
        back={{ href: toHref("/quotations"), label: "Quotations" }}
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">{v.number}</span> <Status value={v.expired && v.status === "OPEN" ? "EXPIRED" : v.status} />
          </span>
        }
        description={`${date(v.date)} · ${v.actorName}`}
        actions={
          open &&
          can(P.QUOTATIONS_MANAGE) && (
            <>
              {can(P.POS_SELL) && (
                <Button onClick={() => setDialog("convert")}>
                  <ShoppingCart /> Convert to sale
                </Button>
              )}
              <Button variant="danger" onClick={() => setDialog("cancel")}>
                <Ban /> Cancel
              </Button>
            </>
          )
        }
      />
      <Panel padded={false} title="Items">
        <Table>
          <thead>
            <tr>
              <Th>Item</Th>
              <Th align="right">Qty</Th>
              <Th align="right">Price</Th>
              <Th align="right">Discount</Th>
              <Th align="right">Total</Th>
            </tr>
          </thead>
          <tbody>
            {v.lines.map((l, i) => (
              <tr key={i}>
                <Td>{l.productName}</Td>
                <Td align="right">
                  {qty(l.qty)} {l.unitName}
                </Td>
                <Td align="right">{money(l.price, false)}</Td>
                <Td align="right">{l.discount !== "0.00" ? money(l.discount, false) : "—"}</Td>
                <Td align="right">{money(l.total, false)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <dl className="grid grid-cols-2 gap-2 border-t border-line p-4 text-sm sm:ml-auto sm:max-w-xs">
          <dt className="text-muted">Subtotal</dt>
          <dd className="text-right font-mono">{money(v.subtotal)}</dd>
          {v.discount !== "0.00" && (
            <>
              <dt className="text-muted">Discount</dt>
              <dd className="text-right font-mono">−{money(v.discount)}</dd>
            </>
          )}
          {v.tax !== "0.00" && (
            <>
              <dt className="text-muted">Tax</dt>
              <dd className="text-right font-mono">{money(v.tax)}</dd>
            </>
          )}
          <dt className="font-semibold">Total</dt>
          <dd className="text-right font-mono text-lg font-semibold">{money(v.total)}</dd>
        </dl>
      </Panel>
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Customer", value: v.customerName ? `${v.customerName}${v.customerPhone ? ` · ${v.customerPhone}` : ""}` : null },
            { label: "Valid until", value: date(v.validUntil) },
            { label: "Note", value: v.note },
            {
              label: "Sale",
              value: v.convertedSaleId ? (
                <ShopLink href={`/sales/${v.convertedSaleId}`} className="text-aqua">
                  Open the sale
                </ShopLink>
              ) : null,
            },
          ]}
        />
      </Panel>
      {dialog === "convert" && <ConvertDialog quotation={v} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={dialog === "cancel"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={`Cancel ${v.number}?`}
        confirmLabel="Cancel quotation"
        danger
        loading={cancel.isPending}
        onConfirm={() => cancel.mutate()}
      />
    </>
  );
}

function ConvertDialog({ quotation, onClose }: { quotation: Schemas["QuotationView"]; onClose: () => void }) {
  const router = useShopRouter();
  const approval = useApproval();
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  const methods = useQuery({ queryKey: ["payment-methods"], queryFn: () => api<Schemas["PaymentMethodView"][]>("/payment-methods") });
  const session = sessions.data?.find((s) => s.branchId === quotation.branchId) ?? null;
  const [method, setMethod] = React.useState("CASH");
  const [amount, setAmount] = React.useState(quotation.total);
  const convert = useMutation({
    mutationFn: () =>
      approval.run((headers) =>
        api<Schemas["SaleView"]>(`/quotations/${quotation.id}/convert`, {
          method: "POST",
          body: { tillSessionId: session?.id, payments: amount ? [{ method, amount }] : [] },
          headers: { "Idempotency-Key": `quote-${quotation.id}`, ...headers },
        }),
      ),
    onSuccess: (sale) => {
      toast.success(`Sale ${sale.number} made`);
      router.push(`/sales/${sale.id}`);
    },
    onError: (e) => {
      if (!(e instanceof ApiError && e.code === "approval_required")) toast.error(errorMessage(e));
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Sell ${quotation.number}`}
      description="Stock and prices are checked again now. Anything short goes on the customer's account."
      footer={
        <Button onClick={() => convert.mutate()} loading={convert.isPending} disabled={!session}>
          Make the sale
        </Button>
      }
    >
      {!session && sessions.isSuccess && <p className="mb-3 text-sm text-warn">Open your till at this branch first (Point of sale).</p>}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Paid by">
          <Select value={method} onChange={(e) => setMethod(e.target.value)}>
            {(methods.data ?? [])
              .filter((m) => m.active && m.key !== "ACCOUNT" && m.key !== "CREDIT_NOTE")
              .map((m) => (
                <option key={m.key} value={m.key}>
                  {m.label}
                </option>
              ))}
          </Select>
        </Field>
        <Field label="Amount">
          <AmountInput value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
      </div>
      {approval.dialog}
    </Dialog>
  );
}
