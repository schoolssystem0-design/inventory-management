"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLink, KeyRound, LifeBuoy } from "lucide-react";
import { useParams } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { useCapability } from "@/components/shell/platform-shell";
import { Button } from "@/components/ui/button";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { AmountInput, Field, Input, Select, Switch, Textarea } from "@/components/ui/fields";
import { Badge, DetailList, ErrorState, PageHeader, Panel, Skeleton, Tabs, statusTone } from "@/components/ui/layout";
import { ImportResultView } from "@/features/catalog/import-result";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, dateTime, label, money } from "@/lib/format";
import { ShopUsersTab } from "./shop-users-tab";

type Profile = Schemas["ShopProfile"];
type Tab = "overview" | "modules" | "subscription" | "owner" | "users" | "import" | "danger";

/** PLT-10..18: one shop's profile, modules, plan, owner access, support session and closing. */
export default function ShopProfilePage() {
  const { id } = useParams<{ id: string }>();
  const cap = useCapability();
  const client = useQueryClient();
  const [tab, setTab] = React.useState<Tab>("overview");
  const profile = useQuery({ queryKey: ["platform-shop", id], queryFn: () => api<Profile>(`/platform/shops/${id}`) });
  const update = (p: Profile) => {
    client.setQueryData(["platform-shop", id], p);
    client.invalidateQueries({ queryKey: ["platform-shops"] });
  };
  const [supportReason, setSupportReason] = React.useState<string | null>(null);
  const support = useMutation({
    mutationFn: (reason: string) => api("/platform/support-session", { method: "POST", body: { shopId: Number(id), reason } }),
    onSuccess: () => {
      window.location.href = window.location.origin + "/support";
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (profile.isPending) return <Skeleton className="h-96" />;
  if (profile.isError) return <ErrorState message={errorMessage(profile.error)} onRetry={() => profile.refetch()} />;
  const p = profile.data;
  const usage = (n: number, max?: number | null) => (max == null ? `${n}` : `${n} of ${max}`);

  return (
    <>
      <PageHeader
        back={{ href: "/shops", label: "Shops" }}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {p.name} <Badge tone={statusTone(p.status)}>{label(p.status)}</Badge>
          </span>
        }
        description={
          <span>
            <span className="font-mono">{p.code}</span> · {p.typeName}
            {p.statusReason && <span className="ml-2 text-warn">({p.statusReason})</span>}
          </span>
        }
        actions={
          <>
            <a href={p.shopUrl} target="_blank" rel="noreferrer" className="inline-flex h-10 items-center gap-2 rounded-xl bg-raised px-4 text-sm font-semibold hover:bg-[#2b2b2e]">
              <ExternalLink className="size-4" /> Shop sign-in page
            </a>
            {cap("platform.support") && (
              <Button onClick={() => setSupportReason("")}>
                <LifeBuoy /> Open as support
              </Button>
            )}
          </>
        }
      />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "overview", label: "Overview" },
          { value: "modules", label: "Features" },
          { value: "subscription", label: "Plan and billing" },
          { value: "owner", label: "Owner" },
          { value: "users", label: "Users" },
          { value: "import", label: "Import products", hidden: !cap("platform.import") },
          { value: "danger", label: "Suspend or close", hidden: !cap("platform.shops.manage") && !cap("platform.billing") && !cap("platform.shops.delete") },
        ]}
      />
      {tab === "overview" && <Overview p={p} onSaved={update} canEdit={cap("platform.shops.manage")} usage={usage} />}
      {tab === "modules" && <Modules p={p} onSaved={update} canEdit={cap("platform.shops.manage")} />}
      {tab === "subscription" && <Subscription p={p} onSaved={update} canEdit={cap("platform.shops.manage") || cap("platform.billing")} />}
      {tab === "owner" && (
        <Owner
          p={p}
          canManage={cap("platform.shops.manage")}
          canLink={cap("platform.shops.manage") || cap("platform.support")}
          onSaved={() => profile.refetch()}
        />
      )}
      {tab === "users" && <ShopUsersTab shopId={p.id} canManage={cap("platform.shops.manage")} />}
      {tab === "import" && <ImportForClient shopId={p.id} />}
      {tab === "danger" && <Danger p={p} onSaved={update} cap={cap} />}

      {supportReason !== null && (
        <Dialog
          open
          onOpenChange={(o) => !o && setSupportReason(null)}
          title={`Open ${p.name} as support`}
          description="You can look at everything. To make changes, the owner gives you a one-hour code. Everything you do is recorded."
          footer={
            <Button onClick={() => support.mutate(supportReason)} loading={support.isPending} disabled={!supportReason.trim()}>
              Open shop
            </Button>
          }
        >
          <Field label="Why are you opening this shop?">
            <Input value={supportReason} onChange={(e) => setSupportReason(e.target.value)} autoFocus placeholder="Owner asked for help with the import" />
          </Field>
        </Dialog>
      )}
    </>
  );
}

function Overview({ p, onSaved, canEdit, usage }: { p: Profile; onSaved: (p: Profile) => void; canEdit: boolean; usage: (n: number, max?: number | null) => string }) {
  const [form, setForm] = React.useState({ name: p.name, city: p.city ?? "", phone: p.phone ?? "", email: p.email ?? "", address: p.address ?? "", ntn: p.ntn ?? "", ownerName: p.ownerName, notes: p.notes ?? "" });
  const save = useMutation({
    mutationFn: () => api<Profile>(`/platform/shops/${p.id}`, { method: "PATCH", body: form }),
    onSuccess: (r) => {
      onSaved(r);
      toast.success("Saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <div className="grid gap-5 lg:grid-cols-3">
      <Panel title="Details" className="lg:col-span-2">
        <fieldset disabled={!canEdit} className="grid gap-4 sm:grid-cols-2">
          <Field label="Name">
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Contact person">
            <Input value={form.ownerName} onChange={(e) => setForm({ ...form, ownerName: e.target.value })} />
          </Field>
          <Field label="City">
            <Input value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} />
          </Field>
          <Field label="Phone">
            <Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          </Field>
          <Field label="Email">
            <Input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          </Field>
          <Field label="NTN">
            <Input value={form.ntn} onChange={(e) => setForm({ ...form, ntn: e.target.value })} />
          </Field>
          <Field label="Address" className="sm:col-span-2">
            <Input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
          </Field>
          <Field label="Internal notes" className="sm:col-span-2">
            <Textarea value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} rows={2} />
          </Field>
        </fieldset>
        {canEdit && (
          <Button className="mt-4" onClick={() => save.mutate()} loading={save.isPending}>
            Save details
          </Button>
        )}
      </Panel>
      <div className="space-y-5">
        <Panel title="Usage">
          <DetailList
            items={[
              { label: "Users", value: usage(p.usage.users, p.limits.users) },
              { label: "Branches", value: usage(p.usage.branches, p.limits.branches) },
              { label: "Products", value: usage(p.usage.products, p.limits.products) },
              { label: "Tills", value: usage(p.usage.tills, p.limits.tills) },
            ]}
          />
        </Panel>
        <Panel title="Branches">
          <ul className="space-y-1 text-sm">
            {p.branches.map((b) => (
              <li key={b.id} className={b.active ? "" : "text-faint line-through"}>
                {b.name} <span className="text-xs text-faint">({label(b.kind)})</span>
              </li>
            ))}
          </ul>
        </Panel>
        <Panel title="Record">
          <DetailList
            items={[
              { label: "Created", value: dateTime(p.createdAt) },
              { label: "Trial ends", value: date(p.trialEndsOn) },
              ...(p.deleteAfter ? [{ label: "Data deleted after", value: date(p.deleteAfter) }] : []),
            ]}
          />
        </Panel>
      </div>
    </div>
  );
}

function Modules({ p, onSaved, canEdit }: { p: Profile; onSaved: (p: Profile) => void; canEdit: boolean }) {
  const [showAll, setShowAll] = React.useState(false);
  const save = useMutation({
    mutationFn: (change: Record<string, boolean>) => api<Profile>(`/platform/shops/${p.id}/modules`, { method: "PUT", body: change }),
    onSuccess: (r) => {
      onSaved(r);
      toast.success("Features updated");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const list = p.modules.filter((m) => showAll || m.standard || m.offered || m.enabled);
  return (
    <Panel
      title="Features"
      description="Super admins can switch anything; owners can switch the optional features of their shop type"
      actions={
        <Button size="sm" variant="ghost" onClick={() => setShowAll(!showAll)}>
          {showAll ? "Only this type's" : "Show all features"}
        </Button>
      }
      padded={false}
    >
      <ul className="divide-y divide-line">
        {list.map((m) => (
          <li key={m.key} className="flex items-center gap-4 px-5 py-3">
            <div className="min-w-0 flex-1">
              <p className="text-sm">{m.label}</p>
              <p className="text-xs text-faint">
                {m.standard ? "Standard" : m.offered ? "Optional" : "Not usual for this type"}
                {m.source && ` · set by ${m.source.toLowerCase()}`}
                {!m.allowedByPlan && " · not in the plan"}
                {m.phase > 1 && " · built in a later release"}
              </p>
            </div>
            <Switch checked={m.enabled} label={m.label} disabled={!canEdit || save.isPending} onChange={(on) => save.mutate({ [m.key]: on })} />
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function Subscription({ p, onSaved, canEdit }: { p: Profile; onSaved: (p: Profile) => void; canEdit: boolean }) {
  const plans = useQuery({ queryKey: ["plans"], queryFn: () => api<Schemas["PlanView"][]>("/platform/plans") });
  const sub = p.subscription;
  const [form, setForm] = React.useState({ planKey: sub?.planKey ?? "", cycle: sub?.cycle ?? "MONTH", price: sub?.price ?? "", discount: sub?.discount ?? "", nextDueOn: sub?.nextDueOn ?? "" });
  const save = useMutation({
    mutationFn: () => {
      const body: Schemas["SubscriptionUpdate"] = {};
      if (form.planKey !== sub?.planKey) body.planKey = form.planKey;
      if (form.cycle !== sub?.cycle) body.cycle = form.cycle;
      if (form.price !== sub?.price) body.price = form.price;
      if (form.discount !== sub?.discount) body.discount = form.discount;
      if (form.nextDueOn !== sub?.nextDueOn) body.nextDueOn = form.nextDueOn;
      return api<Profile>(`/platform/shops/${p.id}/subscription`, { method: "PATCH", body });
    },
    onSuccess: (r) => {
      onSaved(r);
      setForm({ planKey: r.subscription?.planKey ?? "", cycle: r.subscription?.cycle ?? "MONTH", price: r.subscription?.price ?? "", discount: r.subscription?.discount ?? "", nextDueOn: r.subscription?.nextDueOn ?? "" });
      toast.success("Subscription saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Plan and billing" description="Invoices, payments and reminders arrive in Stage 3; the plan and price are set here">
      <fieldset disabled={!canEdit} className="grid gap-4 sm:grid-cols-3">
        <Field label="Plan" hint="Changing the plan re-applies its feature rules">
          <Select value={form.planKey} onChange={(e) => setForm({ ...form, planKey: e.target.value, price: "" })}>
            {plans.data?.map((pl) => (
              <option key={pl.key} value={pl.key}>
                {pl.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Billing">
          <Select value={form.cycle} onChange={(e) => setForm({ ...form, cycle: e.target.value, price: "" })}>
            <option value="MONTH">Monthly</option>
            <option value="YEAR">Yearly</option>
          </Select>
        </Field>
        <Field label="Price (Rs)" hint="Empty = list price">
          <AmountInput value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} />
        </Field>
        <Field label="Discount (Rs)">
          <AmountInput value={form.discount} onChange={(e) => setForm({ ...form, discount: e.target.value })} />
        </Field>
        <Field label="Next due">
          <Input type="date" value={form.nextDueOn} onChange={(e) => setForm({ ...form, nextDueOn: e.target.value })} />
        </Field>
        <Field label="Setup fee">
          <Input value={money(sub?.setupFee)} disabled />
        </Field>
      </fieldset>
      {canEdit && (
        <Button className="mt-4" onClick={() => save.mutate()} loading={save.isPending}>
          Save
        </Button>
      )}
    </Panel>
  );
}

function Owner({ p, canManage, canLink, onSaved }: { p: Profile; canManage: boolean; canLink: boolean; onSaved: () => void }) {
  const [link, setLink] = React.useState<string | null>(null);
  const [tempPassword, setTempPassword] = React.useState<string | null>(null);
  const [change, setChange] = React.useState({ userId: "", reason: "" });
  const sendLink = useMutation({
    mutationFn: () => api<Schemas["SetupLink"]>(`/platform/shops/${p.id}/owner/setup-link`, { method: "POST" }),
    onSuccess: (r) => {
      setLink(r.link);
      toast.success(r.emailed ? "Setup link emailed" : "Setup link made (not emailed)");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const changeOwner = useMutation({
    mutationFn: () => api(`/platform/shops/${p.id}/owner/change`, { method: "POST", body: { userId: Number(change.userId), reason: change.reason } }),
    onSuccess: () => {
      toast.success("Owner changed");
      setChange({ userId: "", reason: "" });
      onSaved();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const resetOwner = useMutation({
    mutationFn: ({ byLink }: { byLink: boolean }) =>
      api<Schemas["PasswordReset"]>(`/platform/shops/${p.id}/users/${p.owner!.userId}/reset-password`, {
        method: "POST",
        body: { byLink },
      }),
    onSuccess: (r) => {
      if (r.temporaryPassword) setTempPassword(r.temporaryPassword);
      else toast.success(r.linkSent ? "Reset link emailed" : "Reset link created (email may not be configured)");
      onSaved();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <div className="grid gap-5 lg:grid-cols-2">
      <Panel title="Owner">
        {p.owner ? (
          <DetailList
            items={[
              { label: "Name", value: p.owner.displayName },
              { label: "Username", value: <span className="font-mono">{p.owner.username}</span> },
              { label: "Email", value: p.owner.email },
              { label: "Phone", value: p.owner.phone },
              { label: "Last sign-in", value: p.owner.lastLoginAt ? dateTime(p.owner.lastLoginAt) : "Never" },
              { label: "Password set", value: p.owner.passwordSet ? "Yes" : "Not yet" },
            ]}
          />
        ) : (
          <p className="text-sm text-muted">No owner.</p>
        )}
        {canLink && p.owner && (
          <div className="mt-5 space-y-3">
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => sendLink.mutate()} loading={sendLink.isPending}>
                New setup link
              </Button>
              {canManage && (
                <>
                  <Button variant="secondary" onClick={() => resetOwner.mutate({ byLink: false })} loading={resetOwner.isPending}>
                    <KeyRound /> Temporary password
                  </Button>
                  {p.owner.email && (
                    <Button variant="secondary" onClick={() => resetOwner.mutate({ byLink: true })} loading={resetOwner.isPending}>
                      Email reset link
                    </Button>
                  )}
                </>
              )}
            </div>
            <p className="text-xs text-faint">
              Setup link: first-time password. Temporary password or email reset: for a user who already has an account.
            </p>
            {link && <Input readOnly value={link} className="font-mono text-xs" aria-label="Setup link" />}
            {tempPassword && (
              <Input readOnly value={tempPassword} className="font-mono text-xs" aria-label="Temporary password" />
            )}
          </div>
        )}
      </Panel>
      {canManage && (
        <Panel title="Change the owner" description="The new owner must already be a user of the shop. The old owner becomes a branch manager.">
          <div className="space-y-4">
            <Field label="User id of the new owner" hint="From the shop's Users page (open it as support)">
              <AmountInput value={change.userId} onChange={(e) => setChange({ ...change, userId: e.target.value.replace(/\D/g, "") })} />
            </Field>
            <Field label="Reason">
              <Input value={change.reason} onChange={(e) => setChange({ ...change, reason: e.target.value })} placeholder="Shop sold to new owner" />
            </Field>
            <Button variant="danger" onClick={() => changeOwner.mutate()} loading={changeOwner.isPending} disabled={!change.userId || !change.reason.trim()}>
              Change owner
            </Button>
          </div>
        </Panel>
      )}
    </div>
  );
}

function ImportForClient({ shopId }: { shopId: number }) {
  const [file, setFile] = React.useState<File | null>(null);
  const [result, setResult] = React.useState<Schemas["ImportResult"] | null>(null);
  const [busy, setBusy] = React.useState(false);
  async function send(dryRun: boolean) {
    if (!file) return;
    setBusy(true);
    const form = new FormData();
    form.append("file", file);
    try {
      const r = await api<Schemas["ImportResult"]>(`/platform/shops/${shopId}/import/products?dryRun=${dryRun}`, { method: "POST", form });
      setResult(r);
      if (!dryRun) toast.success("Products imported into the shop");
    } catch (e) {
      if (e instanceof ApiError && Array.isArray(e.extra.errors)) setResult({ committed: false, rows: 0, toCreate: 0, toUpdate: 0, errors: e.extra.errors as Schemas["ImportResult"]["errors"], newCategories: [], newBrands: [], newUnits: [], ignoredColumns: [] });
      toast.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Panel title="Import the client's products" description="Same rules as the shop's own import: check first, then save all rows or none">
      <input type="file" accept=".xlsx,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} className="text-sm" aria-label="Product file" />
      <div className="mt-4 flex gap-2">
        <Button onClick={() => send(true)} disabled={!file} loading={busy}>
          Check
        </Button>
        {result && !result.committed && result.errors.length === 0 && result.rows > 0 && (
          <Button onClick={() => send(false)} loading={busy}>
            Save {result.rows} rows
          </Button>
        )}
      </div>
      {result && <div className="mt-5"><ImportResultView result={result} /></div>}
    </Panel>
  );
}

function Danger({ p, onSaved, cap }: { p: Profile; onSaved: (p: Profile) => void; cap: (c: string) => boolean }) {
  const [reason, setReason] = React.useState("");
  const [closeCode, setCloseCode] = React.useState("");
  const [closeReason, setCloseReason] = React.useState("");
  const [confirm, setConfirm] = React.useState<"suspend" | "close" | null>(null);
  const act = useMutation({
    mutationFn: (what: "suspend" | "reactivate" | "close") =>
      api<Profile>(`/platform/shops/${p.id}/${what}`, {
        method: "POST",
        body: what === "suspend" ? { reason } : what === "close" ? { confirmCode: closeCode, reason: closeReason } : undefined,
      }),
    onSuccess: (r) => {
      onSaved(r);
      setConfirm(null);
      toast.success(`Shop is now ${label(r.status).toLowerCase()}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const canSuspend = cap("platform.shops.manage") || cap("platform.billing");
  const suspended = p.status === "SUSPENDED" || p.status === "CLOSED";
  return (
    <div className="grid gap-5 lg:grid-cols-2">
      {canSuspend && (
        <Panel title={suspended ? "Reactivate" : "Suspend"} description={suspended ? "Users can sign in again at once" : "All users are signed out at once and cannot sign in. Nothing is deleted."}>
          {suspended ? (
            <Button onClick={() => act.mutate("reactivate")} loading={act.isPending}>
              Reactivate shop
            </Button>
          ) : (
            <div className="space-y-4">
              <Field label="Reason (shown on the shop's sign-in page)">
                <Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Subscription not paid for 2 months" />
              </Field>
              <Button variant="danger" onClick={() => setConfirm("suspend")} disabled={!reason.trim()}>
                Suspend shop
              </Button>
            </div>
          )}
        </Panel>
      )}
      {cap("platform.shops.delete") && p.status !== "CLOSED" && (
        <Panel title="Close the shop" description="The client has left. Data is kept for 90 days, then deleted. Reactivating within 90 days brings it back.">
          <div className="space-y-4">
            <Field label={`Type the shop code (${p.code}) to confirm`}>
              <Input value={closeCode} onChange={(e) => setCloseCode(e.target.value)} className="font-mono" autoComplete="off" />
            </Field>
            <Field label="Reason">
              <Input value={closeReason} onChange={(e) => setCloseReason(e.target.value)} />
            </Field>
            <Button variant="danger" onClick={() => setConfirm("close")} disabled={closeCode.trim().toLowerCase() !== p.code || !closeReason.trim()}>
              Close shop
            </Button>
          </div>
        </Panel>
      )}
      <ConfirmDialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm === "close" ? `Close ${p.name}?` : `Suspend ${p.name}?`}
        description={confirm === "close" ? "Everyone is signed out. The data is deleted after 90 days." : "Everyone is signed out now and cannot sign in until you reactivate the shop."}
        confirmLabel={confirm === "close" ? "Close shop" : "Suspend"}
        danger
        loading={act.isPending}
        onConfirm={() => confirm && act.mutate(confirm)}
      />
    </div>
  );
}
