"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, HandCoins, Mail, Pencil, Power } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { daysAgoIso, todayIso } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/fields";
import { DetailList, Empty, ErrorState, PageHeader, Pager, Panel, Skeleton, StatCard, Table, Tabs, Td, Th } from "@/components/ui/layout";
import { CustomerPlans, LoyaltyPanel } from "@/features/parties/customer-modules";
import { LedgerTable, PartyDialog, PaymentEntryDialog } from "@/features/parties/party-form";
import { api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { P, ShopLink, useBranch, useCan, useFeature, useShopHref } from "@/lib/shop-context";

type Customer = Schemas["CustomerView"];

export default function CustomerPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const feature = useFeature();
  const toHref = useShopHref();
  const client = useQueryClient();
  const [branch] = useBranch();
  const [tab, setTab] = React.useState<"ledger" | "invoices" | "statement" | "loyalty" | "plans">("ledger");
  const [dialog, setDialog] = React.useState<"edit" | "pay" | "email" | null>(null);
  const customer = useQuery({ queryKey: ["customer", id], queryFn: () => api<Customer>(`/customers/${id}`) });
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  const toggle = useMutation({
    mutationFn: (on: boolean) => api(`/customers/${id}/${on ? "activate" : "deactivate"}`, { method: "POST" }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["customer", id] }),
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (customer.isPending) return <Skeleton className="h-96" />;
  if (customer.isError) return <ErrorState message={errorMessage(customer.error)} onRetry={() => customer.refetch()} />;
  const c = customer.data;
  const session = sessions.data?.find((s) => s.branchId === branch?.id) ?? sessions.data?.[0] ?? null;

  return (
    <>
      <PageHeader
        back={{ href: toHref("/customers"), label: "Customers" }}
        title={c.name}
        description={`${c.code} · ${label(c.kind)}${c.phone ? ` · ${c.phone}` : ""}`}
        actions={
          <>
            {can(P.CUSTOMERS_PAYMENTS) && (
              <Button onClick={() => setDialog("pay")}>
                <HandCoins /> Receive payment
              </Button>
            )}
            {can(P.CUSTOMERS_MANAGE) && (
              <>
                <Button variant="secondary" onClick={() => setDialog("edit")}>
                  <Pencil /> Edit
                </Button>
                <Button variant="ghost" onClick={() => toggle.mutate(!c.active)} loading={toggle.isPending}>
                  <Power /> {c.active ? "Switch off" : "Switch on"}
                </Button>
              </>
            )}
          </>
        }
      />
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="Owes you" value={money(c.balance)} tone={c.balance.startsWith("-") ? "good" : c.balance === "0.00" ? undefined : "warn"} />
        <StatCard label="Credit limit" value={c.creditLimit ? money(c.creditLimit) : "No limit"} />
        <StatCard label="Opening balance" value={money(c.openingBalance)} />
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "ledger", label: "Ledger" },
          { value: "invoices", label: "Unpaid bills" },
          { value: "statement", label: "Statement" },
          ...(feature("loyalty") ? [{ value: "loyalty" as const, label: "Loyalty points" }] : []),
          ...(feature("installments") ? [{ value: "plans" as const, label: "Installments" }] : []),
        ]}
      />
      {tab === "ledger" && <Ledger id={c.id} />}
      {tab === "invoices" && <OpenInvoices id={c.id} />}
      {tab === "statement" && <StatementPanel customer={c} onEmail={() => setDialog("email")} />}
      {tab === "loyalty" && <LoyaltyPanel customerId={c.id} />}
      {tab === "plans" && <CustomerPlans customerId={c.id} />}
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Email", value: c.email },
            { label: "CNIC", value: c.cnic },
            { label: "Address", value: c.address },
            { label: "Notes", value: c.notes },
            { label: "Status", value: c.active ? "Active" : "Switched off" },
          ]}
        />
      </Panel>
      {dialog === "edit" && <PartyDialog kind="customer" existing={c} onClose={() => setDialog(null)} />}
      {dialog === "pay" && (
        <PaymentEntryDialog kind="customer" partyId={c.id} partyName={c.name} balance={c.balance} tillSessionId={session?.id ?? null} onClose={() => setDialog(null)} />
      )}
      {dialog === "email" && <EmailStatement customer={c} onClose={() => setDialog(null)} />}
    </>
  );
}

function Ledger({ id }: { id: number }) {
  const [page, setPage] = React.useState(0);
  const toHref = useShopHref();
  const ledger = useQuery({
    queryKey: ["customer-ledger", id, page],
    queryFn: () => api<Schemas["PageResponseLedgerLine"]>(`/customers/${id}/ledger?page=${page}&size=50`),
  });
  return (
    <Panel padded={false}>
      {ledger.isPending ? (
        <Skeleton className="m-5 h-40" />
      ) : ledger.isError ? (
        <ErrorState message={errorMessage(ledger.error)} />
      ) : ledger.data.items.length === 0 ? (
        <Empty title="No entries yet" />
      ) : (
        <>
          <LedgerTable lines={ledger.data.items} debitLabel="Sold (owes more)" creditLabel="Paid / returned" docHref={(l) => (l.docType === "SALE" && l.docId ? toHref(`/sales/${l.docId}`) : null)} />
          <Pager page={page} size={50} total={ledger.data.total} onPage={setPage} />
        </>
      )}
    </Panel>
  );
}

function OpenInvoices({ id }: { id: number }) {
  const list = useQuery({
    queryKey: ["customer-open", id],
    queryFn: () => api<Schemas["PageResponseSaleListItem"]>(`/customers/${id}/open-invoices?size=100`),
  });
  return (
    <Panel padded={false}>
      {list.data?.items.length === 0 && <Empty title="Nothing unpaid" />}
      {list.data && list.data.items.length > 0 && (
        <Table>
          <thead>
            <tr>
              <Th>Invoice</Th>
              <Th>Date</Th>
              <Th align="right">Total</Th>
              <Th align="right">Due</Th>
              <Th>Status</Th>
            </tr>
          </thead>
          <tbody>
            {list.data.items.map((s) => (
              <tr key={s.id}>
                <Td>
                  <ShopLink href={`/sales/${s.id}`} className="font-mono hover:text-aqua">
                    {s.number}
                  </ShopLink>
                </Td>
                <Td className="text-muted">{date(s.date)}</Td>
                <Td align="right">{money(s.total, false)}</Td>
                <Td align="right" className="text-warn">
                  {money(s.due, false)}
                </Td>
                <Td>
                  <Status value={s.status === "POSTED" ? "COMPLETED" : s.status} />
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}

function StatementPanel({ customer, onEmail }: { customer: Customer; onEmail: () => void }) {
  const [from, setFrom] = React.useState(daysAgoIso(30));
  const [to, setTo] = React.useState(todayIso());
  const statement = useQuery({
    queryKey: ["customer-statement", customer.id, from, to],
    queryFn: () => api<Schemas["Statement"]>(`/customers/${customer.id}/statement?from=${from}&to=${to}`),
  });
  return (
    <Panel
      padded={false}
      title="Statement"
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-8 w-auto py-0" aria-label="From" />
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-8 w-auto py-0" aria-label="To" />
          <Button
            size="sm"
            variant="secondary"
            onClick={async () => {
              try {
                saveBlob(await apiBlob(`/customers/${customer.id}/statement.pdf?from=${from}&to=${to}`), `statement-${customer.code}.pdf`);
              }
              catch (e) {
                toast.error(errorMessage(e));
              }
            }}
          >
            <Download /> PDF
          </Button>
          <Button size="sm" variant="secondary" onClick={onEmail}>
            <Mail /> Email
          </Button>
        </div>
      }
    >
      {statement.data && (
        <>
          <div className="flex flex-wrap gap-6 px-5 py-3 text-sm">
            <span>
              Opening <span className="font-mono">{money(statement.data.opening)}</span>
            </span>
            <span>
              Sold <span className="font-mono">{money(statement.data.totalDebit)}</span>
            </span>
            <span>
              Paid <span className="font-mono">{money(statement.data.totalCredit)}</span>
            </span>
            <span className="font-semibold">
              Closing <span className="font-mono">{money(statement.data.closing)}</span>
            </span>
          </div>
          {statement.data.lines.length ? <LedgerTable lines={statement.data.lines} debitLabel="Debit" creditLabel="Credit" /> : <Empty title="No entries in these dates" />}
        </>
      )}
      {statement.isPending && <Skeleton className="m-5 h-40" />}
    </Panel>
  );
}

function EmailStatement({ customer, onClose }: { customer: Customer; onClose: () => void }) {
  const [email, setEmail] = React.useState(customer.email ?? "");
  const send = useMutation({
    mutationFn: () => api(`/customers/${customer.id}/statement/send?from=${daysAgoIso(30)}&to=${todayIso()}`, { method: "POST", body: { email } }),
    onSuccess: () => {
      toast.success("Statement sent");
      onClose();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Email the statement"
      description="The last 30 days as a PDF."
      footer={
        <Button onClick={() => send.mutate()} loading={send.isPending} disabled={!email.includes("@")}>
          Send
        </Button>
      }
    >
      <Field label="Email">
        <Input value={email} onChange={(e) => setEmail(e.target.value)} type="email" autoFocus />
      </Field>
    </Dialog>
  );
}
