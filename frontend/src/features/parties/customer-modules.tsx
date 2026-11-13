"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/fields";
import { Empty, ErrorState, Pager, Panel, Skeleton, StatCard, Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { P, ShopLink, useCan } from "@/lib/shop-context";

/** A customer's loyalty points: balance, when they expire, history and changes by hand (CUS-06). */
export function LoyaltyPanel({ customerId }: { customerId: number }) {
  const can = useCan();
  const [page, setPage] = React.useState(0);
  const [adjusting, setAdjusting] = React.useState(false);
  const account = useQuery({ queryKey: ["loyalty", customerId], queryFn: () => api<Schemas["LoyaltyAccount"]>(`/customers/${customerId}/loyalty`) });
  const history = useQuery({
    queryKey: ["loyalty-history", customerId, page],
    queryFn: () => api<{ items: Schemas["LoyaltyTxnView"][]; total: number }>(`/customers/${customerId}/loyalty/history?page=${page}&size=25`),
  });
  if (account.isPending) return <Skeleton className="h-40" />;
  if (account.isError) return <ErrorState message={errorMessage(account.error)} />;
  const a = account.data;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Points" value={a.points.toLocaleString()} hint={`Worth ${money(a.value)}`} tone="aqua" />
        <StatCard label="Last purchase" value={a.lastPurchase ? date(a.lastPurchase) : "—"} />
        <StatCard label="Points expire" value={a.expiresOn ? date(a.expiresOn) : "—"} hint={a.settings.expiryMonths ? `after ${a.settings.expiryMonths} months without buying` : "never"} />
      </div>
      <Panel
        title="Points history"
        description={`One point for every ${money(a.settings.spendPerPoint)} paid; each point is worth ${money(a.settings.pointValue)}.`}
        padded={false}
        actions={
          can(P.LOYALTY_MANAGE) && (
            <Button size="sm" variant="secondary" onClick={() => setAdjusting(true)}>
              Change points
            </Button>
          )
        }
      >
        {history.data?.items.length === 0 ? (
          <Empty title="No points yet" />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Date</Th>
                  <Th>What</Th>
                  <Th>Invoice</Th>
                  <Th align="right">Points</Th>
                  <Th align="right">Balance</Th>
                </tr>
              </thead>
              <tbody>
                {history.data?.items.map((t) => (
                  <tr key={t.id}>
                    <Td className="text-muted">{date(t.date)}</Td>
                    <Td>
                      {label(t.kind)}
                      {t.note && <span className="block text-xs text-faint">{t.note}</span>}
                    </Td>
                    <Td>{t.saleId ? <ShopLink href={`/sales/${t.saleId}`} className="font-mono hover:text-aqua">{t.saleNumber}</ShopLink> : "—"}</Td>
                    <Td align="right" className={t.points < 0 ? "text-warn" : "text-good"}>
                      {t.points > 0 ? `+${t.points}` : t.points}
                    </Td>
                    <Td align="right">{t.balanceAfter}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            {history.data && <Pager page={page} size={25} total={history.data.total} onPage={setPage} />}
          </>
        )}
      </Panel>
      {adjusting && <AdjustPoints customerId={customerId} onClose={() => setAdjusting(false)} />}
    </div>
  );
}

function AdjustPoints({ customerId, onClose }: { customerId: number; onClose: () => void }) {
  const client = useQueryClient();
  const [points, setPoints] = React.useState("");
  const [note, setNote] = React.useState("");
  const save = useMutation({
    mutationFn: () => api(`/customers/${customerId}/loyalty/adjust`, { method: "POST", body: { points: Number(points), note } }),
    onSuccess: () => {
      toast.success("Points changed");
      client.invalidateQueries({ queryKey: ["loyalty", customerId] });
      client.invalidateQueries({ queryKey: ["loyalty-history", customerId] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Change points"
      description="Add points (or minus to remove). The reason is kept in the history."
      footer={
        <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!/^-?\d+$/.test(points) || points === "0" || !note.trim()}>
          Save
        </Button>
      }
    >
      <div className="space-y-3">
        <Field label="Points">
          <Input value={points} onChange={(e) => setPoints(e.target.value)} inputMode="numeric" placeholder="100 or -50" autoFocus />
        </Field>
        <Field label="Reason" required>
          <Input value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        {save.error && <p className="text-sm text-bad">{errorMessage(save.error)}</p>}
      </div>
    </Dialog>
  );
}

/** A customer's installment plans. */
export function CustomerPlans({ customerId }: { customerId: number }) {
  const plans = useQuery({
    queryKey: ["installment-plans", "customer", customerId],
    queryFn: () => api<{ items: Schemas["InstallmentPlanView"][] }>(`/installment-plans?customerId=${customerId}&size=50`),
  });
  return (
    <Panel padded={false}>
      {plans.isPending ? (
        <Skeleton className="m-5 h-24" />
      ) : plans.isError ? (
        <ErrorState message={errorMessage(plans.error)} />
      ) : plans.data.items.length === 0 ? (
        <Empty title="No installment plans" description="Open an invoice on this customer's account and choose Installment plan." />
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Plan</Th>
              <Th>Invoice</Th>
              <Th align="right">Total</Th>
              <Th align="right">Left</Th>
              <Th>Next due</Th>
              <Th>Status</Th>
            </tr>
          </thead>
          <tbody>
            {plans.data.items.map((p) => (
              <tr key={p.id}>
                <Td>
                  <ShopLink href={`/installments/${p.id}`} className="font-mono hover:text-aqua">
                    {p.number}
                  </ShopLink>
                </Td>
                <Td className="font-mono text-muted">{p.saleNumber}</Td>
                <Td align="right">{money(p.total, false)}</Td>
                <Td align="right">{money(p.left, false)}</Td>
                <Td>{p.nextDueOn ? `${date(p.nextDueOn)} · ${money(p.nextDueAmount)}` : "—"}</Td>
                <Td>
                  <Status value={p.overdueCount ? "LATE" : p.status} />
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}
