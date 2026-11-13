"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, HandCoins, PackagePlus, Pencil, Plus, Power } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { daysAgoIso, todayIso } from "@/components/list-page";
import { Button, LinkButton } from "@/components/ui/button";
import { Input } from "@/components/ui/fields";
import { DetailList, Empty, ErrorState, PageHeader, Pager, Panel, Skeleton, StatCard, Tabs } from "@/components/ui/layout";
import { LedgerTable, PartyDialog, PaymentEntryDialog } from "@/features/parties/party-form";
import { api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";
import { P, useBranch, useCan, useShopHref } from "@/lib/shop-context";

type Supplier = Schemas["SupplierView"];

export default function SupplierPage() {
  const { id } = useParams<{ id: string }>();
  const can = useCan();
  const toHref = useShopHref();
  const client = useQueryClient();
  const [branch] = useBranch();
  const [tab, setTab] = React.useState<"ledger" | "statement">("ledger");
  const [dialog, setDialog] = React.useState<"edit" | "pay" | null>(null);
  const supplier = useQuery({ queryKey: ["supplier", id], queryFn: () => api<Supplier>(`/suppliers/${id}`) });
  const toggle = useMutation({
    mutationFn: (on: boolean) => api(`/suppliers/${id}/${on ? "activate" : "deactivate"}`, { method: "POST" }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["supplier", id] }),
    onError: (e) => toast.error(errorMessage(e)),
  });
  if (supplier.isPending) return <Skeleton className="h-96" />;
  if (supplier.isError) return <ErrorState message={errorMessage(supplier.error)} onRetry={() => supplier.refetch()} />;
  const s = supplier.data;
  return (
    <>
      <PageHeader
        back={{ href: toHref("/suppliers"), label: "Suppliers" }}
        title={s.name}
        description={[s.code, s.company, s.phone].filter(Boolean).join(" · ")}
        actions={
          <>
            {can(P.SUPPLIERS_PAYMENTS) && (
              <Button onClick={() => setDialog("pay")}>
                <HandCoins /> Pay supplier
              </Button>
            )}
            {can(P.PURCHASING_MANAGE) && (
              <>
                <LinkButton variant="secondary" href={toHref(`/purchases/new?supplier=${s.id}`)}>
                  <Plus /> Order
                </LinkButton>
                <LinkButton variant="secondary" href={toHref(`/purchases/receive?supplier=${s.id}`)}>
                  <PackagePlus /> Receive goods
                </LinkButton>
                <Button variant="ghost" onClick={() => setDialog("edit")}>
                  <Pencil /> Edit
                </Button>
                <Button variant="ghost" onClick={() => toggle.mutate(!s.active)}>
                  <Power /> {s.active ? "Switch off" : "Switch on"}
                </Button>
              </>
            )}
          </>
        }
      />
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="You owe" value={money(s.balance)} tone={s.balance === "0.00" ? undefined : "warn"} />
        <StatCard label="Payment terms" value={s.termsDays ? `${s.termsDays} days` : "Cash"} />
        <StatCard label="Opening balance" value={money(s.openingBalance)} />
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "ledger", label: "Ledger" },
          { value: "statement", label: "Statement" },
        ]}
      />
      {tab === "ledger" ? <Ledger id={s.id} /> : <Statement supplier={s} />}
      <Panel title="Details" className="mt-4">
        <DetailList
          items={[
            { label: "Email", value: s.email },
            { label: "NTN", value: s.ntn },
            { label: "Address", value: s.address },
            { label: "Notes", value: s.notes },
          ]}
        />
      </Panel>
      {dialog === "edit" && <PartyDialog kind="supplier" existing={s} onClose={() => setDialog(null)} />}
      {dialog === "pay" && <PaymentEntryDialog kind="supplier" partyId={s.id} partyName={s.name} balance={s.balance} branchId={branch?.id} onClose={() => setDialog(null)} />}
    </>
  );
}

function Ledger({ id }: { id: number }) {
  const [page, setPage] = React.useState(0);
  const toHref = useShopHref();
  const ledger = useQuery({
    queryKey: ["supplier-ledger", id, page],
    queryFn: () => api<Schemas["PageResponseSupplierLedgerLine"]>(`/suppliers/${id}/ledger?page=${page}&size=50`),
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
          <LedgerTable
            lines={ledger.data.items}
            debitLabel="Paid / returned"
            creditLabel="Bought (you owe more)"
            docHref={(l) => (l.docType === "GRN" && l.docId ? toHref(`/purchases/grns/${l.docId}`) : null)}
          />
          <Pager page={page} size={50} total={ledger.data.total} onPage={setPage} />
        </>
      )}
    </Panel>
  );
}

function Statement({ supplier }: { supplier: Supplier }) {
  const [from, setFrom] = React.useState(daysAgoIso(30));
  const [to, setTo] = React.useState(todayIso());
  const st = useQuery({
    queryKey: ["supplier-statement", supplier.id, from, to],
    queryFn: () => api<Schemas["SupplierStatement"]>(`/suppliers/${supplier.id}/statement?from=${from}&to=${to}`),
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
                saveBlob(await apiBlob(`/suppliers/${supplier.id}/statement.pdf?from=${from}&to=${to}`), `statement-${supplier.code}.pdf`);
              }
              catch (e) {
                toast.error(errorMessage(e));
              }
            }}
          >
            <Download /> PDF
          </Button>
        </div>
      }
    >
      {st.data && (
        <>
          <div className="flex flex-wrap gap-6 px-5 py-3 text-sm">
            <span>
              Opening <span className="font-mono">{money(st.data.opening)}</span>
            </span>
            <span>
              Closing <span className="font-mono font-semibold">{money(st.data.closing)}</span>
            </span>
          </div>
          {st.data.lines.length ? <LedgerTable lines={st.data.lines} debitLabel="Debit" creditLabel="Credit" /> : <Empty title="No entries in these dates" />}
        </>
      )}
    </Panel>
  );
}
