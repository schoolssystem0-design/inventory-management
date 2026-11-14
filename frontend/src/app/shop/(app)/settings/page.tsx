"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ImagePlus, KeyRound } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AmountInput, Checkbox, Field, Input, Select, Switch, Textarea } from "@/components/ui/fields";
import { Badge, ErrorState, PageHeader, Pager, Panel, Skeleton, Table, TableSkeleton, Tabs, Td, Th } from "@/components/ui/layout";
import { ApiError, api, errorMessage, fileUrl, type Schemas } from "@/lib/api/client";
import { BRAND } from "@/lib/brand";
import { dateTime, label } from "@/lib/format";
import { P, useCan, useFeature, useRefreshContext, useShop } from "@/lib/shop-context";

type Settings = Schemas["SettingsView"];
type Update = Schemas["SettingsUpdate"];
type Tab = "business" | "receipts" | "tax" | "modules" | "security" | "numbering" | "payments" | "features" | "support" | "history";

/** Shop settings (SET-01..08) with change history, numbering, payment methods, features and support access. */
export default function SettingsPage() {
  const can = useCan();
  const feature = useFeature();
  const moduleRules = feature("scale_barcodes") || feature("prescriptions") || feature("loyalty");
  const [tab, setTab] = React.useState<Tab>("business");
  const settings = useQuery({ queryKey: ["settings"], queryFn: () => api<Settings>("/settings") });
  const edit = can(P.SETTINGS_EDIT);

  return (
    <>
      <PageHeader title="Settings" description={edit ? undefined : "You can view settings; only the owner can change them"} />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "business", label: "Business" },
          { value: "receipts", label: "Receipts" },
          { value: "tax", label: "Tax and stock" },
          { value: "modules", label: "Shop-type rules", hidden: !moduleRules },
          { value: "security", label: "Security" },
          { value: "numbering", label: "Numbering" },
          { value: "payments", label: "Payment methods" },
          { value: "features", label: "Features" },
          { value: "support", label: "Support access", hidden: !can(P.SUPPORT_APPROVE) },
          { value: "history", label: "Change history" },
        ]}
      />
      {settings.isPending ? (
        <Skeleton className="h-80" />
      ) : settings.isError ? (
        <ErrorState message={errorMessage(settings.error)} onRetry={() => settings.refetch()} />
      ) : (
        <>
          {tab === "business" && <BusinessTab settings={settings.data} edit={edit} />}
          {tab === "receipts" && <ReceiptsTab settings={settings.data} edit={edit} />}
          {tab === "tax" && <TaxTab settings={settings.data} edit={edit} />}
          {tab === "modules" && <ModulesTab settings={settings.data} edit={edit} />}
          {tab === "security" && <SecurityTab settings={settings.data} edit={edit} />}
        </>
      )}
      {tab === "numbering" && <NumberingTab edit={edit} />}
      {tab === "payments" && <PaymentsTab edit={edit} />}
      {tab === "features" && <FeaturesTab edit={edit} />}
      {tab === "support" && <SupportTab />}
      {tab === "history" && <HistoryTab />}
    </>
  );
}

/** Edits a group of settings; only changed fields are sent. */
function useSettingsForm<T extends Record<string, unknown>>(initial: T) {
  const client = useQueryClient();
  const refresh = useRefreshContext();
  const [values, setValues] = React.useState<T>(initial);
  const [error, setError] = React.useState<ApiError | null>(null);
  const save = useMutation({
    mutationFn: () => {
      const changed: Record<string, unknown> = {};
      for (const key of Object.keys(values)) {
        if (values[key] !== initial[key]) changed[key] = values[key];
      }
      return api<Settings>("/settings", { method: "PATCH", body: changed as Update });
    },
    onSuccess: (s) => {
      client.setQueryData(["settings"], s);
      client.invalidateQueries({ queryKey: ["settings-history"] });
      refresh();
      setError(null);
      toast.success("Settings saved");
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e : null);
      toast.error(errorMessage(e));
    },
  });
  const set = <K extends keyof T>(key: K, value: T[K]) => setValues((v) => ({ ...v, [key]: value }));
  return { values, set, save, error, dirty: Object.keys(values).some((k) => values[k] !== initial[k]) };
}

function SaveBar({ form, edit }: { form: { save: { mutate: () => void; isPending: boolean }; dirty: boolean }; edit: boolean }) {
  if (!edit) return null;
  return (
    <div className="mt-5 flex justify-end">
      <Button onClick={() => form.save.mutate()} loading={form.save.isPending} disabled={!form.dirty}>
        Save changes
      </Button>
    </div>
  );
}

function BusinessTab({ settings, edit }: { settings: Settings; edit: boolean }) {
  const form = useSettingsForm({
    businessName: settings.businessName ?? "",
    urduName: settings.urduName ?? "",
    address: settings.address ?? "",
    phones: settings.phones ?? "",
    email: settings.email ?? "",
    ntn: settings.ntn ?? "",
    strn: settings.strn ?? "",
  });
  const client = useQueryClient();
  const refresh = useRefreshContext();
  const v = form.values;
  async function uploadLogo(file: File) {
    const data = new FormData();
    data.append("file", file);
    try {
      const saved = await api<Schemas["FileInfo"]>("/files?purpose=SHOP_LOGO", { method: "POST", form: data });
      const s = await api<Settings>("/settings", { method: "PATCH", body: { logoFileId: saved.id } });
      client.setQueryData(["settings"], s);
      refresh();
      toast.success("Logo saved");
    } catch (e) {
      toast.error(errorMessage(e));
    }
  }
  return (
    <div className="grid gap-5 lg:grid-cols-3">
      <Panel title="Business details" description="Printed on receipts and invoices" className="lg:col-span-2">
        <fieldset disabled={!edit} className="grid gap-4 sm:grid-cols-2">
          <Field label="Business name" required error={form.error?.field("businessName")}>
            <Input value={v.businessName} onChange={(e) => form.set("businessName", e.target.value)} />
          </Field>
          <Field label="Name in Urdu">
            <Input value={v.urduName} onChange={(e) => form.set("urduName", e.target.value)} dir="rtl" className="urdu" />
          </Field>
          <Field label="Address" className="sm:col-span-2">
            <Textarea value={v.address} onChange={(e) => form.set("address", e.target.value)} rows={2} />
          </Field>
          <Field label="Phone numbers">
            <Input value={v.phones} onChange={(e) => form.set("phones", e.target.value)} />
          </Field>
          <Field label="Email" error={form.error?.field("email")}>
            <Input type="email" value={v.email} onChange={(e) => form.set("email", e.target.value)} />
          </Field>
          <Field label="NTN">
            <Input value={v.ntn} onChange={(e) => form.set("ntn", e.target.value)} />
          </Field>
          <Field label="STRN (sales tax)">
            <Input value={v.strn} onChange={(e) => form.set("strn", e.target.value)} />
          </Field>
        </fieldset>
        <SaveBar form={form} edit={edit} />
      </Panel>
      <Panel title="Logo">
        {settings.logoFileId ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={fileUrl(settings.logoFileId) ?? ""} alt="Shop logo" className="mb-3 aspect-square w-full rounded-xl bg-white object-contain" />
        ) : (
          <div className="mb-3 grid aspect-square w-full place-items-center rounded-xl bg-deep text-faint">
            <ImagePlus className="size-8" aria-hidden />
          </div>
        )}
        {edit && (
          <label className="block cursor-pointer rounded-xl bg-raised px-3 py-2 text-center text-sm font-semibold hover:bg-[#2b2b2e]">
            {settings.logoFileId ? "Change logo" : "Upload logo"}
            <input type="file" accept="image/png,image/jpeg,image/webp" className="sr-only" onChange={(e) => e.target.files?.[0] && uploadLogo(e.target.files[0])} />
          </label>
        )}
      </Panel>
    </div>
  );
}

function ReceiptsTab({ settings, edit }: { settings: Settings; edit: boolean }) {
  const form = useSettingsForm({
    receiptSize: settings.receiptSize,
    receiptLanguage: settings.receiptLanguage,
    receiptHeader: settings.receiptHeader ?? "",
    receiptFooter: settings.receiptFooter ?? "",
    returnPolicy: settings.returnPolicy ?? "",
    receiptShowBarcode: settings.receiptShowBarcode,
    receiptShowTax: settings.receiptShowTax,
    receiptShowSavings: settings.receiptShowSavings,
  });
  const v = form.values;
  return (
    <Panel title="Receipts">
      <fieldset disabled={!edit} className="grid gap-4 sm:grid-cols-2">
        <Field label="Paper">
          <Select value={v.receiptSize} onChange={(e) => form.set("receiptSize", e.target.value)}>
            {["MM80", "MM58", "A4"].map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Language">
          <Select value={v.receiptLanguage} onChange={(e) => form.set("receiptLanguage", e.target.value)}>
            {["EN", "UR", "BOTH"].map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Top message" className="sm:col-span-2">
          <Input value={v.receiptHeader} onChange={(e) => form.set("receiptHeader", e.target.value)} maxLength={500} />
        </Field>
        <Field label="Bottom message" className="sm:col-span-2">
          <Input value={v.receiptFooter} onChange={(e) => form.set("receiptFooter", e.target.value)} maxLength={500} />
        </Field>
        <Field label="Return policy" className="sm:col-span-2">
          <Textarea value={v.returnPolicy} onChange={(e) => form.set("returnPolicy", e.target.value)} rows={2} maxLength={500} />
        </Field>
        <Checkbox label="Print the invoice barcode" checked={v.receiptShowBarcode} onChange={(e) => form.set("receiptShowBarcode", e.target.checked)} />
        <Checkbox label="Show tax" checked={v.receiptShowTax} onChange={(e) => form.set("receiptShowTax", e.target.checked)} />
        <Checkbox label="Show what the customer saved" checked={v.receiptShowSavings} onChange={(e) => form.set("receiptShowSavings", e.target.checked)} />
      </fieldset>
      <SaveBar form={form} edit={edit} />
    </Panel>
  );
}

function TaxTab({ settings, edit }: { settings: Settings; edit: boolean }) {
  const form = useSettingsForm({
    taxMode: settings.taxMode,
    taxRate: settings.taxRate,
    allowNegativeStock: settings.allowNegativeStock,
    priceRounding: settings.priceRounding,
    returnDays: settings.returnDays,
    discountLimitPct: settings.discountLimitPct,
    writeoffLimit: settings.writeoffLimit,
    closingTime: settings.closingTime,
  });
  const v = form.values;
  const err = (f: string) => form.error?.field(f);
  return (
    <div className="grid gap-5 lg:grid-cols-2">
      <Panel title="Sales tax">
        <fieldset disabled={!edit} className="grid gap-4">
          <Field label="How prices include tax">
            <Select value={v.taxMode} onChange={(e) => form.set("taxMode", e.target.value)}>
              {["NONE", "INCLUSIVE", "EXCLUSIVE"].map((s) => (
                <option key={s} value={s}>
                  {label(s)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Tax rate %" error={err("taxRate")}>
            <AmountInput value={v.taxRate} onChange={(e) => form.set("taxRate", e.target.value)} disabled={v.taxMode === "NONE"} />
          </Field>
        </fieldset>
      </Panel>
      <Panel title="Stock and selling rules">
        <fieldset disabled={!edit} className="grid gap-4 sm:grid-cols-2">
          <Checkbox
            className="sm:col-span-2"
            label="Allow selling more than is in stock"
            hint="Off: the till refuses to sell what is not in stock"
            checked={v.allowNegativeStock}
            onChange={(e) => form.set("allowNegativeStock", e.target.checked)}
          />
          <Field label="Discount limit %" hint="Above this a manager's PIN is needed" error={err("discountLimitPct")}>
            <AmountInput value={v.discountLimitPct} onChange={(e) => form.set("discountLimitPct", e.target.value)} />
          </Field>
          <Field label="Write-off limit (Rs)" hint="Damaged/lost stock above this needs approval" error={err("writeoffLimit")}>
            <AmountInput value={v.writeoffLimit} onChange={(e) => form.set("writeoffLimit", e.target.value)} />
          </Field>
          <Field label="Returns accepted for (days)" error={err("returnDays")}>
            <AmountInput value={String(v.returnDays)} onChange={(e) => form.set("returnDays", Number(e.target.value.replace(/\D/g, "")) || 0)} />
          </Field>
          <Field label="Round sale prices to">
            <Select value={v.priceRounding} onChange={(e) => form.set("priceRounding", e.target.value)}>
              <option value="NONE">No rounding</option>
              <option value="ONE">Nearest 1</option>
              <option value="FIVE">Nearest 5</option>
              <option value="TEN">Nearest 10</option>
            </Select>
          </Field>
          <Field label="Shop closing time" hint="Used for the daily summary" error={err("closingTime")}>
            <Input type="time" value={v.closingTime} onChange={(e) => form.set("closingTime", e.target.value)} />
          </Field>
        </fieldset>
      </Panel>
      <div className="lg:col-span-2">
        <SaveBar form={form} edit={edit} />
      </div>
    </div>
  );
}

/** Rules of the Stage 4b modules: scale labels, prescriptions and loyalty points. */
function ModulesTab({ settings, edit }: { settings: Settings; edit: boolean }) {
  const feature = useFeature();
  const form = useSettingsForm({
    scaleValueKind: settings.scaleValueKind,
    prescriptionRequired: settings.prescriptionRequired,
    loyaltySpendPerPoint: settings.loyaltySpendPerPoint,
    loyaltyPointValue: settings.loyaltyPointValue,
    loyaltyMinRedeem: settings.loyaltyMinRedeem,
    loyaltyExpiryMonths: settings.loyaltyExpiryMonths,
  });
  const v = form.values;
  const err = (f: string) => form.error?.field(f);
  return (
    <div className="grid gap-5 lg:grid-cols-2">
      {feature("scale_barcodes") && (
        <Panel title="Weighing-scale labels" description="Labels starting 20–29: item code (the product's scale code), then the value.">
          <fieldset disabled={!edit} className="grid gap-4">
            <Field label="The scale prints" error={err("scaleValueKind")}>
              <Select value={v.scaleValueKind} onChange={(e) => form.set("scaleValueKind", e.target.value)}>
                <option value="WEIGHT">The weight (grams)</option>
                <option value="PRICE">The price (whole rupees)</option>
              </Select>
            </Field>
          </fieldset>
        </Panel>
      )}
      {feature("prescriptions") && (
        <Panel title="Prescriptions">
          <fieldset disabled={!edit} className="grid gap-4">
            <Checkbox
              label="Ask for a prescription for Rx medicines"
              hint="Controlled medicines always need one, and a pharmacist's PIN"
              checked={v.prescriptionRequired}
              onChange={(e) => form.set("prescriptionRequired", e.target.checked)}
            />
          </fieldset>
        </Panel>
      )}
      {feature("loyalty") && (
        <Panel title="Loyalty points">
          <fieldset disabled={!edit} className="grid gap-4 sm:grid-cols-2">
            <Field label="One point for every (Rs)" error={err("loyaltySpendPerPoint")}>
              <AmountInput value={v.loyaltySpendPerPoint} onChange={(e) => form.set("loyaltySpendPerPoint", e.target.value)} />
            </Field>
            <Field label="A point is worth (Rs)" error={err("loyaltyPointValue")}>
              <AmountInput value={v.loyaltyPointValue} onChange={(e) => form.set("loyaltyPointValue", e.target.value)} />
            </Field>
            <Field label="Least points used at once" error={err("loyaltyMinRedeem")}>
              <AmountInput value={String(v.loyaltyMinRedeem)} onChange={(e) => form.set("loyaltyMinRedeem", Number(e.target.value.replace(/\D/g, "")) || 0)} />
            </Field>
            <Field label="Points expire after (months without buying)" hint="0 = never" error={err("loyaltyExpiryMonths")}>
              <AmountInput value={String(v.loyaltyExpiryMonths)} onChange={(e) => form.set("loyaltyExpiryMonths", Number(e.target.value.replace(/\D/g, "")) || 0)} />
            </Field>
          </fieldset>
        </Panel>
      )}
      <div className="lg:col-span-2">
        <SaveBar form={form} edit={edit} />
      </div>
    </div>
  );
}

function SecurityTab({ settings, edit }: { settings: Settings; edit: boolean }) {
  const form = useSettingsForm({
    sessionIdleMinutes: settings.sessionIdleMinutes,
    ownerTotpRequired: settings.ownerTotpRequired,
    maskCustomerPii: settings.maskCustomerPii,
  });
  const v = form.values;
  return (
    <Panel title="Security">
      <fieldset disabled={!edit} className="grid max-w-xl gap-4">
        <Field label="Sign out after this many idle minutes" hint="2 to 480. The till locks with a PIN instead (from Stage 4)." error={form.error?.field("sessionIdleMinutes")}>
          <AmountInput value={String(v.sessionIdleMinutes)} onChange={(e) => form.set("sessionIdleMinutes", Number(e.target.value.replace(/\D/g, "")) || 0)} />
        </Field>
        <Checkbox label="Ask the owner for an authenticator code at sign-in" hint="Turn it on under My account" checked={v.ownerTotpRequired} onChange={(e) => form.set("ownerTotpRequired", e.target.checked)} />
        <Checkbox label="Hide customers' CNIC and phone on prints" checked={v.maskCustomerPii} onChange={(e) => form.set("maskCustomerPii", e.target.checked)} />
      </fieldset>
      <SaveBar form={form} edit={edit} />
    </Panel>
  );
}

function NumberingTab({ edit }: { edit: boolean }) {
  const client = useQueryClient();
  const numbering = useQuery({ queryKey: ["numbering"], queryFn: () => api<Schemas["NumberingView"][]>("/numbering") });
  const [draft, setDraft] = React.useState<Record<number, { prefix: string; nextNo: string }>>({});
  const save = useMutation({
    mutationFn: ({ id, prefix, nextNo }: { id: number; prefix: string; nextNo: string }) =>
      api(`/numbering/${id}`, { method: "PATCH", body: { prefix, nextNo: Number(nextNo) } }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["numbering"] });
      toast.success("Numbering saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const NAMES: Record<string, string> = {
    SALE: "Sale invoices",
    SALE_RETURN: "Returns",
    QUOTATION: "Quotations",
    CUSTOMER_PAYMENT: "Customer receipts",
    PURCHASE_ORDER: "Purchase orders",
    GRN: "Goods received",
    PURCHASE_RETURN: "Returns to suppliers",
    SUPPLIER_PAYMENT: "Supplier payments",
    ADJUSTMENT: "Stock adjustments",
    STOCK_COUNT: "Stock counts",
    TRANSFER: "Transfers",
    EXPENSE: "Expenses",
  };
  return (
    <Panel title="Document numbers" description="Numbers only go up, so a number is never used twice" padded={false}>
      {numbering.isPending ? (
        <TableSkeleton />
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Document</Th>
              <Th>Prefix</Th>
              <Th>Next number</Th>
              <Th>Looks like</Th>
              {edit && <Th />}
            </tr>
          </thead>
          <tbody>
            {numbering.data?.map((n) => {
              const d = draft[n.id] ?? { prefix: n.prefix, nextNo: String(n.nextNo) };
              const changed = d.prefix !== n.prefix || d.nextNo !== String(n.nextNo);
              return (
                <tr key={n.id}>
                  <Td>{NAMES[n.docType] ?? label(n.docType)}</Td>
                  <Td>
                    <Input value={d.prefix} disabled={!edit} onChange={(e) => setDraft({ ...draft, [n.id]: { ...d, prefix: e.target.value.toUpperCase() } })} className="w-28 font-mono" aria-label={`Prefix for ${n.docType}`} />
                  </Td>
                  <Td>
                    <AmountInput value={d.nextNo} disabled={!edit} onChange={(e) => setDraft({ ...draft, [n.id]: { ...d, nextNo: e.target.value.replace(/\D/g, "") } })} className="w-28" aria-label={`Next number for ${n.docType}`} />
                  </Td>
                  <Td className="font-mono text-muted">{n.example}</Td>
                  {edit && (
                    <Td>
                      <Button size="sm" variant="secondary" disabled={!changed} onClick={() => save.mutate({ id: n.id, ...d })}>
                        Save
                      </Button>
                    </Td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}

function PaymentsTab({ edit }: { edit: boolean }) {
  const client = useQueryClient();
  const methods = useQuery({ queryKey: ["payment-methods"], queryFn: () => api<Schemas["PaymentMethodView"][]>("/payment-methods") });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Schemas["PaymentMethodUpdate"] }) => api(`/payment-methods/${id}`, { method: "PATCH", body }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["payment-methods"] }),
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Payment methods" description="Which ways of paying the till offers" padded={false}>
      {methods.isPending ? (
        <TableSkeleton />
      ) : (
        <ul className="divide-y divide-line">
          {methods.data?.map((m) => (
            <li key={m.id} className="flex items-center gap-4 px-5 py-3">
              <Input
                defaultValue={m.label}
                disabled={!edit}
                aria-label={`Label for ${m.key}`}
                className="max-w-xs"
                onBlur={(e) => e.target.value.trim() !== m.label && update.mutate({ id: m.id, body: { label: e.target.value } })}
              />
              <span className="flex-1 font-mono text-xs text-faint">{m.key}</span>
              <Switch checked={m.active} label={`${m.label} on`} disabled={!edit || m.key === "CASH"} onChange={(active) => update.mutate({ id: m.id, body: { active } })} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function FeaturesTab({ edit }: { edit: boolean }) {
  const client = useQueryClient();
  const refresh = useRefreshContext();
  const shop = useShop();
  const modules = useQuery({ queryKey: ["modules"], queryFn: () => api<Schemas["ModuleState"][]>("/modules") });
  const toggle = useMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) => api<Schemas["ModuleState"][]>(`/modules/${key}`, { method: "PUT", body: { enabled } }),
    onSuccess: (list) => {
      client.setQueryData(["modules"], list);
      refresh();
      toast.success("Saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Features" description={`What a ${shop.shop.typeName.toLowerCase()} can switch on. Data is kept when a feature is switched off.`} padded={false}>
      {modules.isPending ? (
        <TableSkeleton />
      ) : (
        <ul className="divide-y divide-line">
          {modules.data?.map((m) => {
            const locked = !m.ownerCanToggle;
            const blocked = !m.allowedByPlan && !m.enabled;
            return (
              <li key={m.key} className="flex items-center gap-4 px-5 py-3">
                <div className="min-w-0 flex-1">
                  <p className="text-sm">{m.label}</p>
                  <p className="text-xs text-faint">
                    {m.standard ? "Standard for this shop type" : "Optional"}
                    {m.phase > 1 && " · arrives in a later release"}
                    {locked && ` · changed by ${BRAND.support()}`}
                    {blocked && " · not in your plan"}
                  </p>
                </div>
                {m.enabled ? <Badge tone="good">On</Badge> : <Badge>Off</Badge>}
                <Switch checked={m.enabled} label={m.label} disabled={!edit || locked || blocked || toggle.isPending} onChange={(enabled) => toggle.mutate({ key: m.key, enabled })} />
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

function SupportTab() {
  const [code, setCode] = React.useState<Schemas["SupportCode"] | null>(null);
  const issue = useMutation({
    mutationFn: () => api<Schemas["SupportCode"]>("/settings/support-code", { method: "POST" }),
    onSuccess: setCode,
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title={`Let ${BRAND.support()} make changes`} description="Support can always look; changes need this code from you">
      <div className="max-w-md space-y-4 text-sm text-muted">
        <p>Give this code to the support person only if you asked them for help. It works once, for one hour.</p>
        {code ? (
          <div className="rounded-xl bg-deep p-4 text-center">
            <p className="font-mono text-3xl tracking-[0.4em] text-aqua">{code.code}</p>
            <p className="mt-2 text-xs text-faint">Valid until {dateTime(code.expiresAt)}</p>
          </div>
        ) : null}
        <Button onClick={() => issue.mutate()} loading={issue.isPending}>
          <KeyRound /> {code ? "Make a new code" : "Make a code"}
        </Button>
      </div>
    </Panel>
  );
}

function HistoryTab() {
  const [page, setPage] = React.useState(0);
  const history = useQuery({
    queryKey: ["settings-history", page],
    queryFn: () => api<Schemas["PageResponseSettingsChangeView"]>(`/settings/history?page=${page}&size=50`),
    placeholderData: keepPreviousData,
  });
  return (
    <Panel title="Change history" padded={false}>
      {history.isPending ? (
        <TableSkeleton />
      ) : (
        <>
          <Table>
            <thead>
              <tr>
                <Th>When</Th>
                <Th>Setting</Th>
                <Th>Was</Th>
                <Th>Now</Th>
                <Th>Who</Th>
              </tr>
            </thead>
            <tbody>
              {history.data?.items.map((h) => (
                <tr key={h.id}>
                  <Td className="whitespace-nowrap text-muted">{dateTime(h.at)}</Td>
                  <Td>{h.key}</Td>
                  <Td className="text-muted">{h.oldValue ?? "—"}</Td>
                  <Td>{h.newValue ?? "—"}</Td>
                  <Td className="text-muted">
                    {h.actorName}
                    {h.actorType === "PLATFORM" && <Badge tone="info" className="ml-2">Support</Badge>}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <Pager page={page} size={50} total={history.data?.total ?? 0} onPage={setPage} />
        </>
      )}
    </Panel>
  );
}
