"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDownToLine, ArrowUpFromLine, Lock, Printer, ShoppingCart } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { type Column, daysAgoIso, ListPage, todayIso } from "@/components/list-page";
import { Button, LinkButton } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Textarea } from "@/components/ui/fields";
import { Empty, ErrorState, PageHeader, Panel, Skeleton, StatCard } from "@/components/ui/layout";
import { printHtml, zReportHtml } from "@/features/receipts/receipt";
import { api, ApiError, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, money } from "@/lib/format";
import { addMoney, isMoney, lineAmount, ZERO } from "@/lib/money";
import { P, useCan, useShop, useShopHref } from "@/lib/shop-context";

type Session = Schemas["SessionView"];

const NOTES = ["5000", "1000", "500", "100", "50", "20", "10", "5", "2", "1"];

/** Till and cash (POS-10): the open till, cash in and out, closing with a count and the Z-report. */
export default function TillPage() {
  const can = useCan();
  const toHref = useShopHref();
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Session[]>("/till-sessions/current") });
  const [zFor, setZFor] = React.useState<number | null>(null);

  return (
    <>
      <PageHeader title="Till and cash" description="Your open till, money in and out of the drawer, and closing." />
      {sessions.isPending && <Skeleton className="h-48" />}
      {sessions.isError && <ErrorState message={errorMessage(sessions.error)} onRetry={() => sessions.refetch()} />}
      {sessions.data?.length === 0 && (
        <Panel>
          <Empty
            title="Your till is not open"
            description="Open it from the point of sale with the cash in the drawer."
            action={
              <LinkButton href={toHref("/pos")}>
                <ShoppingCart /> Go to the till
              </LinkButton>
            }
          />
        </Panel>
      )}
      <div className="space-y-4">
        {sessions.data?.map((s) => <OpenSession key={s.id} session={s} onClosed={(id) => setZFor(id)} />)}
      </div>
      {can(P.TILL_MANAGE) && <History onZ={setZFor} />}
      {zFor !== null && <ZDialog sessionId={zFor} onClose={() => setZFor(null)} />}
    </>
  );
}

function OpenSession({ session, onClosed }: { session: Session; onClosed: (id: number) => void }) {
  const can = useCan();
  const [dialog, setDialog] = React.useState<"IN" | "OUT" | "close" | null>(null);
  const x = useQuery({
    queryKey: ["z-report", session.id],
    queryFn: () => api<Schemas["ZReport"]>(`/till-sessions/${session.id}/z-report`),
    enabled: can(P.TILL_MANAGE) || can(P.CASH_VIEW),
    refetchInterval: 60_000,
  });
  const moves = useQuery({ queryKey: ["cash-moves", session.id], queryFn: () => api<Schemas["CashMovementView"][]>(`/till-sessions/${session.id}/cash-movements`) });
  return (
    <Panel
      title={`${session.tillName} · ${session.branchName}`}
      description={`Opened ${dateTime(session.openedAt)} by ${session.userName} with ${money(session.openingFloat)}`}
      actions={
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={() => setDialog("IN")}>
            <ArrowDownToLine /> Cash in
          </Button>
          <Button variant="secondary" size="sm" onClick={() => setDialog("OUT")}>
            <ArrowUpFromLine /> Cash out
          </Button>
          <Button size="sm" onClick={() => setDialog("close")}>
            <Lock /> Close till
          </Button>
        </div>
      }
    >
      {x.data && (
        <div className="grid gap-3 sm:grid-cols-4">
          <StatCard label="Sales" value={x.data.sales} hint={money(x.data.netSales)} />
          <StatCard label="Cash sales" value={money(x.data.cashSales)} />
          <StatCard label="Returns" value={x.data.returns} hint={money(x.data.returnsValue)} />
          <StatCard label="Cash expected now" value={money(x.data.expectedCash)} tone="aqua" />
        </div>
      )}
      {moves.data && moves.data.length > 0 && (
        <ul className="mt-4 space-y-1 text-sm">
          {moves.data.map((m) => (
            <li key={m.id} className="flex justify-between gap-3">
              <span>
                {m.kind === "IN" ? "Cash in" : "Cash out"} · {m.reason} <span className="text-xs text-faint">{m.actorName} · {dateTime(m.at)}</span>
              </span>
              <span className={m.kind === "IN" ? "font-mono text-good" : "font-mono text-warn"}>
                {m.kind === "IN" ? "+" : "−"}
                {money(m.amount, false)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {(dialog === "IN" || dialog === "OUT") && <CashMoveDialog session={session} kind={dialog} onClose={() => setDialog(null)} />}
      {dialog === "close" && (
        <CloseDialog
          session={session}
          onClose={() => setDialog(null)}
          onClosed={() => {
            setDialog(null);
            onClosed(session.id);
          }}
        />
      )}
    </Panel>
  );
}

function CashMoveDialog({ session, kind, onClose }: { session: Session; kind: "IN" | "OUT"; onClose: () => void }) {
  const client = useQueryClient();
  const [amount, setAmount] = React.useState("");
  const [reason, setReason] = React.useState("");
  const save = useMutation({
    mutationFn: () => api(`/till-sessions/${session.id}/cash-movements`, { method: "POST", body: { kind, amount, reason } }),
    onSuccess: () => {
      toast.success(kind === "IN" ? "Cash in recorded" : "Cash out recorded");
      client.invalidateQueries({ queryKey: ["cash-moves", session.id] });
      client.invalidateQueries({ queryKey: ["z-report", session.id] });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={kind === "IN" ? "Put cash into the drawer" : "Take cash out of the drawer"}
      description={kind === "IN" ? "Change brought from the bank or the owner." : "Cash sent to the owner or the bank. For a shop expense use Expenses."}
      footer={
        <Button type="submit" form="move-form" loading={save.isPending} disabled={!isMoney(amount) || !reason.trim()}>
          Save
        </Button>
      }
    >
      <form
        id="move-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Amount" error={save.error ? errorMessage(save.error) : undefined}>
          <AmountInput value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus />
        </Field>
        <Field label="Reason" required>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
      </form>
    </Dialog>
  );
}

function CloseDialog({ session, onClose, onClosed }: { session: Session; onClose: () => void; onClosed: () => void }) {
  const client = useQueryClient();
  const [count, setCount] = React.useState<Record<string, string>>({});
  const [direct, setDirect] = React.useState("");
  const [note, setNote] = React.useState("");
  const counted = direct || NOTES.reduce((sum, n) => addMoney(sum, /^\d+$/.test(count[n] ?? "") ? lineAmount(count[n], n) : ZERO), ZERO);
  const close = useMutation({
    mutationFn: () => api<Schemas["ZReport"]>(`/till-sessions/${session.id}/close`, { method: "POST", body: { countedCash: counted, note: note || null } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["till-current"] });
      client.invalidateQueries({ queryKey: ["till-sessions"] });
      toast.success("Till closed");
      onClosed();
    },
    onError: (e) => {
      if (!(e instanceof ApiError)) toast.error(errorMessage(e));
    },
  });
  return (
    <Dialog
      open
      wide
      onOpenChange={(o) => !o && onClose()}
      title={`Close ${session.tillName}`}
      description="Count the cash in the drawer. The difference from what the system expects is kept on the Z-report."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => close.mutate()} loading={close.isPending} disabled={!isMoney(counted)}>
            Close with {money(counted)}
          </Button>
        </>
      }
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="grid grid-cols-2 gap-2">
          {NOTES.map((n) => (
            <label key={n} className="flex items-center gap-2 text-sm">
              <span className="w-14 text-right font-mono text-muted">{Number(n).toLocaleString()}</span>
              <span className="text-faint">×</span>
              <Input
                value={count[n] ?? ""}
                onChange={(e) => {
                  setDirect("");
                  setCount((c) => ({ ...c, [n]: e.target.value.replace(/\D/g, "") }));
                }}
                inputMode="numeric"
                className="h-9 w-16 text-right font-mono"
                aria-label={`Number of ${n} rupee notes`}
              />
            </label>
          ))}
        </div>
        <div className="space-y-4">
          <Field label="Or type the total counted">
            <AmountInput value={direct} onChange={(e) => setDirect(e.target.value)} placeholder={counted} />
          </Field>
          <p className="rounded-xl bg-raised/60 p-4 text-center">
            <span className="block text-sm text-muted">Counted</span>
            <span className="font-mono text-3xl font-bold">{money(counted)}</span>
          </p>
          <Field label="Note">
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} className="min-h-12" />
          </Field>
          {close.error && <p className="text-sm text-bad">{errorMessage(close.error)}</p>}
        </div>
      </div>
    </Dialog>
  );
}

function ZDialog({ sessionId, onClose }: { sessionId: number; onClose: () => void }) {
  const shop = useShop();
  const z = useQuery({ queryKey: ["z-report", sessionId, "final"], queryFn: () => api<Schemas["ZReport"]>(`/till-sessions/${sessionId}/z-report`) });
  const html = z.data ? zReportHtml(z.data, shop.settings.businessName || shop.shop.name, shop.settings.receiptSize as "MM80") : null;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Z-report"
      footer={
        <Button onClick={() => html && void printHtml(html)} disabled={!html}>
          <Printer /> Print
        </Button>
      }
    >
      {z.isError && <ErrorState message={errorMessage(z.error)} />}
      {html ? <iframe title="Z-report" srcDoc={html} className="h-[480px] w-full rounded-lg bg-white" /> : !z.isError && <Skeleton className="h-[480px]" />}
    </Dialog>
  );
}

function History({ onZ }: { onZ: (id: number) => void }) {
  const columns: Column<Session>[] = [
    { label: "Till", render: (s) => `${s.tillName} · ${s.branchName}` },
    { label: "Cashier", render: (s) => s.userName },
    { label: "Opened", render: (s) => <span className="text-muted">{dateTime(s.openedAt)}</span> },
    { label: "Closed", render: (s) => (s.closedAt ? <span className="text-muted">{dateTime(s.closedAt)}</span> : <span className="text-aqua">Open</span>) },
    { label: "Expected", render: (s) => money(s.expectedCash, false), align: "right" },
    { label: "Counted", render: (s) => money(s.countedCash, false), align: "right" },
    {
      label: "Difference",
      align: "right",
      render: (s) =>
        s.difference == null ? "—" : <span className={s.difference.startsWith("-") ? "text-bad" : s.difference === "0.00" ? "" : "text-warn"}>{money(s.difference, false)}</span>,
    },
    {
      label: "Z",
      render: (s) => (
        <button className="text-sm font-semibold text-aqua" onClick={() => onZ(s.id)}>
          View
        </button>
      ),
    },
  ];
  return (
    <div className="mt-8">
      <ListPage<Session>
        title="Till history"
        path="/till-sessions"
        queryKey="till-sessions"
        columns={columns}
        filters={[{ kind: "dates", from: daysAgoIso(6), to: todayIso() }]}
        empty={{ title: "No till sessions in these dates" }}
      />
    </div>
  );
}
