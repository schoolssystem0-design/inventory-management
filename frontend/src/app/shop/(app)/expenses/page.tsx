"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Paperclip, Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { branchFilter, type Column, daysAgoIso, ListPage, todayIso } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { api, ApiError, errorMessage, fileUrl, type Schemas } from "@/lib/api/client";
import { date, label, money } from "@/lib/format";
import { isMoney } from "@/lib/money";
import { P, useBranch, useCan, useShop } from "@/lib/shop-context";

type Row = Schemas["ExpenseView"];

export default function ExpensesPage() {
  const can = useCan();
  const shop = useShop();
  const [adding, setAdding] = React.useState(false);
  const [voiding, setVoiding] = React.useState<Row | null>(null);
  const categories = useQuery({ queryKey: ["expense-categories"], queryFn: () => api<Schemas["ExpenseCategoryView"][]>("/expense-categories") });
  const columns: Column<Row>[] = [
    { label: "Date", render: (e) => <span className="text-muted">{date(e.date)}</span> },
    {
      label: "Expense",
      render: (e) => (
        <>
          {e.categoryName}
          <span className="block text-xs text-faint">
            {e.number}
            {e.paidTo ? ` · ${e.paidTo}` : ""}
            {e.note ? ` · ${e.note}` : ""}
          </span>
        </>
      ),
    },
    { label: "Paid by", render: (e) => <span className="text-muted">{label(e.method)}</span> },
    { label: "Branch", render: (e) => <span className="text-muted">{e.branchName}</span>, hidden: shop.branches.length < 2 },
    { label: "Amount", render: (e) => <span className={e.status === "VOID" ? "line-through text-faint" : undefined}>{money(e.amount, false)}</span>, align: "right" },
    {
      label: "",
      render: (e) => (
        <span className="flex items-center justify-end gap-2">
          {e.attachmentFileId && (
            <a href={fileUrl(e.attachmentFileId) ?? "#"} target="_blank" rel="noreferrer" className="text-faint hover:text-aqua" aria-label="Receipt photo">
              <Paperclip className="size-4" />
            </a>
          )}
          {e.status === "VOID" ? (
            <Status value="VOID" />
          ) : (
            can(P.EXPENSES_MANAGE) && (
              <button className="text-xs text-faint hover:text-bad" onClick={() => setVoiding(e)}>
                Void
              </button>
            )
          )}
        </span>
      ),
    },
  ];
  return (
    <>
      <ListPage<Row>
        title="Expenses"
        description="Rent, bills, wages and other shop costs. Cash expenses come out of the till."
        path="/expenses"
        queryKey="expenses"
        columns={columns}
        actions={
          can(P.EXPENSES_MANAGE) && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> New expense
            </Button>
          )
        }
        filters={[
          { kind: "dates", from: daysAgoIso(30), to: todayIso() },
          ...branchFilter(shop.branches),
          {
            kind: "select",
            param: "categoryId",
            label: "Category",
            options: [{ value: "", label: "All categories" }, ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: c.name }))],
          },
        ]}
        empty={{ title: "No expenses in these dates" }}
      />
      {adding && <ExpenseDialog categories={categories.data ?? []} onClose={() => setAdding(false)} />}
      {voiding && <VoidDialog expense={voiding} onClose={() => setVoiding(null)} />}
    </>
  );
}

function ExpenseDialog({ categories, onClose }: { categories: Schemas["ExpenseCategoryView"][]; onClose: () => void }) {
  const client = useQueryClient();
  const shop = useShop();
  const [branch] = useBranch();
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  const [form, setForm] = React.useState({ categoryId: String(categories.find((c) => c.active)?.id ?? ""), amount: "", method: "CASH", paidTo: "", note: "", date: todayIso() });
  const [branchId, setBranchId] = React.useState<number | null>(branch?.id ?? null);
  const [file, setFile] = React.useState<File | null>(null);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const save = useMutation({
    mutationFn: async () => {
      let attachmentFileId: number | null = null;
      if (file) {
        const data = new FormData();
        data.append("file", file);
        attachmentFileId = (await api<Schemas["FileInfo"]>("/files?purpose=EXPENSE_ATTACHMENT", { method: "POST", form: data })).id;
      }
      const session = sessions.data?.find((s) => s.branchId === branchId);
      return api("/expenses", {
        method: "POST",
        body: {
          ...form,
          categoryId: Number(form.categoryId),
          branchId,
          paidTo: form.paidTo || null,
          note: form.note || null,
          attachmentFileId,
          tillSessionId: form.method === "CASH" ? session?.id ?? null : null,
        },
      });
    },
    onSuccess: () => {
      toast.success("Expense saved");
      client.invalidateQueries({ queryKey: ["expenses"] });
      onClose();
    },
  });
  const err = (f: string) => (save.error instanceof ApiError ? save.error.field(f) : undefined);
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="New expense"
      footer={
        <Button type="submit" form="expense-form" loading={save.isPending} disabled={!isMoney(form.amount) || !form.categoryId}>
          Save
        </Button>
      }
    >
      <form
        id="expense-form"
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Category" error={err("categoryId")}>
          <Select value={form.categoryId} onChange={set("categoryId")}>
            {categories
              .filter((c) => c.active)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
          </Select>
        </Field>
        <Field label="Amount" error={err("amount")}>
          <AmountInput value={form.amount} onChange={set("amount")} autoFocus />
        </Field>
        <Field label="Paid by" hint={form.method === "CASH" ? "Taken from your open till if there is one." : undefined}>
          <Select value={form.method} onChange={set("method")}>
            <option value="CASH">Cash</option>
            <option value="BANK">Bank transfer</option>
            <option value="JAZZCASH">JazzCash</option>
            <option value="EASYPAISA">Easypaisa</option>
          </Select>
        </Field>
        <Field label="Date" error={err("date")}>
          <Input type="date" value={form.date} onChange={set("date")} />
        </Field>
        {shop.branches.length > 1 && (
          <Field label="Branch">
            <Select value={branchId ?? ""} onChange={(e) => setBranchId(Number(e.target.value))}>
              {shop.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="Paid to">
          <Input value={form.paidTo} onChange={set("paidTo")} placeholder="WAPDA, landlord…" />
        </Field>
        <Field label="Note" className="sm:col-span-2">
          <Textarea value={form.note} onChange={set("note")} className="min-h-10" />
        </Field>
        <Field label="Photo of the bill" className="sm:col-span-2">
          <input type="file" accept="image/*,application/pdf" capture="environment" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm text-muted" />
        </Field>
        {save.error && <p className="text-sm text-bad sm:col-span-2">{errorMessage(save.error)}</p>}
      </form>
    </Dialog>
  );
}

function VoidDialog({ expense, onClose }: { expense: Row; onClose: () => void }) {
  const client = useQueryClient();
  const [reason, setReason] = React.useState("");
  const voidIt = useMutation({
    mutationFn: () => api(`/expenses/${expense.id}/void`, { method: "POST", body: { reason } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["expenses"] });
      onClose();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Void ${expense.number}?`}
      description="The expense stays in the records, marked void, and no longer counts."
      footer={
        <Button variant="danger" onClick={() => voidIt.mutate()} loading={voidIt.isPending} disabled={!reason.trim()}>
          Void
        </Button>
      }
    >
      <Field label="Reason" required>
        <Textarea value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </Field>
    </Dialog>
  );
}
