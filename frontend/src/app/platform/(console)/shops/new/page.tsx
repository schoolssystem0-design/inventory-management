"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import { Check, Copy } from "lucide-react";
import Link from "next/link";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AmountInput, Checkbox, Field, Input, Select, Textarea } from "@/components/ui/fields";
import { PageHeader, Panel } from "@/components/ui/layout";
import { ApiError, api, type Schemas } from "@/lib/api/client";
import { money, plural } from "@/lib/format";
import { rootDomain, stripPort } from "@/lib/routing";

type NewShop = Schemas["NewShop"];
type Created = Schemas["ShopCreated"];

const STEPS = ["Business", "Shop type", "Plan", "Starting data", "Owner"] as const;

/** PLT-08: the five-step shop creation wizard. Nothing is saved until the last step. */
export default function NewShopPage() {
  const [step, setStep] = React.useState(0);
  const [s, setS] = React.useState({
    name: "",
    code: "",
    city: "",
    address: "",
    phone: "",
    email: "",
    ntn: "",
    typeKey: "",
    extraModules: [] as string[],
    removedModules: [] as string[],
    planKey: "business",
    cycle: "MONTH",
    price: "",
    discount: "",
    setupFee: "",
    trialDays: "14",
    branches: "Main shop",
    taxMode: "NONE",
    taxRate: "",
    receiptLanguage: "EN",
    seedCategories: true,
    ownerName: "",
    ownerUsername: "owner",
    ownerEmail: "",
    ownerPhone: "",
    notes: "",
  });
  const set = <K extends keyof typeof s>(k: K, v: (typeof s)[K]) => setS((p) => ({ ...p, [k]: v }));
  const [error, setError] = React.useState<ApiError | null>(null);
  const [created, setCreated] = React.useState<Created | null>(null);

  const types = useQuery({ queryKey: ["shop-types"], queryFn: () => api<Schemas["ShopTypeView"][]>("/platform/shop-types") });
  const plans = useQuery({ queryKey: ["plans"], queryFn: () => api<Schemas["PlanView"][]>("/platform/plans") });
  const catalogue = useQuery({ queryKey: ["module-catalogue"], queryFn: () => api<{ key: string; label: string; phase: number }[]>("/platform/modules") });

  const [debouncedCode, setDebouncedCode] = React.useState("");
  React.useEffect(() => {
    const t = setTimeout(() => setDebouncedCode(s.code.trim().toLowerCase()), 300);
    return () => clearTimeout(t);
  }, [s.code]);
  const check = useQuery({
    queryKey: ["code-check", debouncedCode, s.name],
    queryFn: () => api<Schemas["CodeCheck"]>(`/platform/shops/code-check?code=${encodeURIComponent(debouncedCode)}&name=${encodeURIComponent(s.name)}`),
    enabled: debouncedCode.length > 0 || s.name.length > 2,
  });

  const type = types.data?.find((t) => t.key === s.typeKey);
  const plan = plans.data?.find((p) => p.key === s.planKey);
  const labelOf = (key: string) => catalogue.data?.find((m) => m.key === key)?.label ?? key;

  const create = useMutation({
    mutationFn: () => {
      const body: NewShop = {
        code: s.code.trim().toLowerCase(),
        name: s.name,
        city: s.city,
        address: s.address,
        phone: s.phone,
        email: s.email || undefined,
        ntn: s.ntn,
        typeKey: s.typeKey,
        extraModules: s.extraModules,
        removedModules: s.removedModules,
        planKey: s.planKey,
        cycle: s.cycle,
        price: s.price || undefined,
        discount: s.discount || undefined,
        setupFee: s.setupFee || undefined,
        trialDays: Number(s.trialDays || 0),
        branches: s.branches.split("\n").map((b) => b.trim()).filter(Boolean),
        taxMode: s.taxMode,
        taxRate: s.taxRate || undefined,
        receiptLanguage: s.receiptLanguage,
        seedCategories: s.seedCategories,
        ownerName: s.ownerName,
        ownerUsername: s.ownerUsername,
        ownerEmail: s.ownerEmail || undefined,
        ownerPhone: s.ownerPhone || undefined,
        notes: s.notes || undefined,
      };
      return api<Created>("/platform/shops", { method: "POST", body });
    },
    onSuccess: setCreated,
    onError: (e) => {
      const err = e instanceof ApiError ? e : null;
      setError(err);
      toast.error(e instanceof Error ? e.message : "Could not create the shop");
      const field = err?.fieldErrors[0]?.field ?? (err?.code === "code_taken" ? "code" : "");
      const stepOf: Record<string, number> = { code: 0, name: 0, email: 0, typeKey: 1, extraModules: 1, removedModules: 1, planKey: 2, cycle: 2, price: 2, discount: 2, setupFee: 2, trialDays: 2, taxMode: 3, taxRate: 3, receiptLanguage: 3, ownerName: 4, ownerEmail: 4, username: 4 };
      if (field in stepOf) setStep(stepOf[field]);
    },
  });

  const canNext = [
    s.name.trim() && s.code.trim() && check.data?.available !== false,
    !!s.typeKey,
    !!s.planKey,
    true,
    s.ownerName.trim().length > 0,
  ][step];

  if (created) {
    return (
      <>
        <PageHeader title="Shop created" />
        <Panel>
          <div className="space-y-4 text-sm">
            <p className="flex items-center gap-2 text-good">
              <Check className="size-5" /> {created.name} is ready ({created.status === "TRIAL" ? "free trial" : "active"}).
            </p>
            <p className="text-muted">
              Send this one-time link to the owner (valid 48 hours). {created.ownerSetup.emailed ? "It was also emailed to them." : "It was not emailed."}
            </p>
            <div className="flex gap-2">
              <Input readOnly value={created.ownerSetup.link} className="font-mono text-xs" aria-label="Owner setup link" />
              <Button
                variant="secondary"
                onClick={() => {
                  navigator.clipboard?.writeText(created.ownerSetup.link);
                  toast.success("Copied");
                }}
              >
                <Copy /> Copy
              </Button>
            </div>
            <p className="text-muted">Switched on: {created.modules.map(labelOf).join(", ") || "core features only"}</p>
            <div className="flex gap-2">
              <Link href={`/shops/${created.shopId}`} className="font-semibold text-aqua hover:underline">
                Open the shop&apos;s page →
              </Link>
            </div>
          </div>
        </Panel>
      </>
    );
  }

  return (
    <>
      <PageHeader title="New shop" back={{ href: "/shops", label: "Shops" }} />
      <ol className="no-scrollbar mb-5 flex gap-2 overflow-x-auto" aria-label="Steps">
        {STEPS.map((name, i) => (
          <li key={name}>
            <button
              onClick={() => i < step && setStep(i)}
              aria-current={i === step ? "step" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-full px-3 py-1.5 text-[13px] font-semibold whitespace-nowrap",
                i === step ? "bg-aqua text-on-aqua" : i < step ? "bg-raised text-ink" : "bg-deep text-faint",
              )}
            >
              <span className="font-mono">{i + 1}</span> {name}
            </button>
          </li>
        ))}
      </ol>

      <Panel>
        {step === 0 && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Shop name" required error={error?.field("name")}>
              <Input
                value={s.name}
                onChange={(e) => set("name", e.target.value)}
                onBlur={() => !s.code && check.data?.suggestion && set("code", check.data.suggestion)}
                autoFocus
              />
            </Field>
            <Field
              label="Shop code"
              required
              hint={check.data?.available === false ? undefined : s.code ? `Address: ${s.code.toLowerCase()}.${stripPort(rootDomain())}` : check.data?.suggestion ? `Suggestion: ${check.data.suggestion}` : "Small letters, numbers and dashes"}
              error={error?.field("code") ?? (error?.code === "code_taken" ? error.message : undefined) ?? (check.data?.available === false && s.code ? `${check.data.reason}. Try ${check.data.suggestion}` : undefined)}
            >
              <Input value={s.code} onChange={(e) => set("code", e.target.value.toLowerCase())} autoCapitalize="none" className="font-mono" maxLength={30} />
            </Field>
            <Field label="City">
              <Input value={s.city} onChange={(e) => set("city", e.target.value)} />
            </Field>
            <Field label="Phone">
              <Input value={s.phone} onChange={(e) => set("phone", e.target.value)} inputMode="tel" />
            </Field>
            <Field label="Address" className="sm:col-span-2">
              <Input value={s.address} onChange={(e) => set("address", e.target.value)} />
            </Field>
            <Field label="Email" error={error?.field("email")}>
              <Input type="email" value={s.email} onChange={(e) => set("email", e.target.value)} />
            </Field>
            <Field label="NTN">
              <Input value={s.ntn} onChange={(e) => set("ntn", e.target.value)} />
            </Field>
          </div>
        )}

        {step === 1 && (
          <div className="space-y-5">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {types.data?.filter((t) => t.active).map((t) => (
                <button
                  key={t.key}
                  onClick={() => {
                    set("typeKey", t.key);
                    set("extraModules", []);
                    set("removedModules", []);
                  }}
                  aria-pressed={s.typeKey === t.key}
                  className={cn("tile p-4 text-left", s.typeKey === t.key && "bg-aqua/15 shadow-[inset_0_0_0_2px_var(--color-aqua)]")}
                >
                  <p className="font-semibold">{t.name}</p>
                  <p className="mt-1 text-xs text-muted">{t.description}</p>
                </button>
              ))}
            </div>
            {type && (
              <div>
                <p className="mb-2 text-sm font-semibold">Features for this shop</p>
                <div className="grid gap-1 sm:grid-cols-2">
                  {[...type.defaultModules, ...type.offeredModules].map((key) => {
                    const standard = type.defaultModules.includes(key);
                    const on = standard ? !s.removedModules.includes(key) : s.extraModules.includes(key);
                    return (
                      <Checkbox
                        key={key}
                        label={labelOf(key)}
                        hint={standard ? "Standard for this type" : "Optional"}
                        checked={on}
                        onChange={(e) => {
                          if (standard) set("removedModules", e.target.checked ? s.removedModules.filter((k) => k !== key) : [...s.removedModules, key]);
                          else set("extraModules", e.target.checked ? [...s.extraModules, key] : s.extraModules.filter((k) => k !== key));
                        }}
                      />
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {step === 2 && (
          <div className="space-y-5">
            <div className="grid gap-3 sm:grid-cols-3">
              {plans.data?.filter((p) => p.active).map((p) => (
                <button
                  key={p.key}
                  onClick={() => set("planKey", p.key)}
                  aria-pressed={s.planKey === p.key}
                  className={cn("tile p-4 text-left", s.planKey === p.key && "bg-aqua/15 shadow-[inset_0_0_0_2px_var(--color-aqua)]")}
                >
                  <p className="font-semibold">{p.name}</p>
                  <p className="mt-1 font-mono text-lg">{money(p.priceMonth)}</p>
                  <p className="text-xs text-faint">a month · {money(p.priceYear)} a year</p>
                  <p className="mt-2 text-xs text-muted">
                    {p.maxUsers ?? "Any number of"} users · {p.maxBranches ?? "any"} branches · {p.maxProducts?.toLocaleString() ?? "unlimited"} products
                  </p>
                </button>
              ))}
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Billing">
                <Select value={s.cycle} onChange={(e) => set("cycle", e.target.value)}>
                  <option value="MONTH">Monthly</option>
                  <option value="YEAR">Yearly</option>
                </Select>
              </Field>
              <Field label="Price (Rs)" hint={plan ? `List price ${money(s.cycle === "YEAR" ? plan.priceYear : plan.priceMonth)}` : undefined} error={error?.field("price")}>
                <AmountInput value={s.price} onChange={(e) => set("price", e.target.value)} placeholder="List price" />
              </Field>
              <Field label="Discount (Rs)" error={error?.field("discount")}>
                <AmountInput value={s.discount} onChange={(e) => set("discount", e.target.value)} />
              </Field>
              <Field label="Setup fee (Rs)" hint={plan ? `Usual ${money(plan.setupFee)}` : undefined} error={error?.field("setupFee")}>
                <AmountInput value={s.setupFee} onChange={(e) => set("setupFee", e.target.value)} />
              </Field>
              <Field label="Free trial (days)" hint="0 = start paid now" error={error?.field("trialDays")}>
                <AmountInput value={s.trialDays} onChange={(e) => set("trialDays", e.target.value.replace(/\D/g, ""))} />
              </Field>
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Branches and godowns" hint="One per line; the first is the main branch. A name with 'godown' becomes a godown.">
              <Textarea value={s.branches} onChange={(e) => set("branches", e.target.value)} rows={4} />
            </Field>
            <div className="space-y-4">
              <Field label="Sales tax">
                <Select value={s.taxMode} onChange={(e) => set("taxMode", e.target.value)}>
                  <option value="NONE">No tax</option>
                  <option value="INCLUSIVE">Included in prices</option>
                  <option value="EXCLUSIVE">Added on top</option>
                </Select>
              </Field>
              {s.taxMode !== "NONE" && (
                <Field label="Tax rate %" error={error?.field("taxRate")}>
                  <AmountInput value={s.taxRate} onChange={(e) => set("taxRate", e.target.value)} />
                </Field>
              )}
              <Field label="Receipt language">
                <Select value={s.receiptLanguage} onChange={(e) => set("receiptLanguage", e.target.value)}>
                  <option value="EN">English</option>
                  <option value="UR">Urdu</option>
                  <option value="BOTH">English and Urdu</option>
                </Select>
              </Field>
              <Checkbox label="Add the usual categories for this shop type" checked={s.seedCategories} onChange={(e) => set("seedCategories", e.target.checked)} />
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Owner's name" required error={error?.field("ownerName")}>
              <Input value={s.ownerName} onChange={(e) => set("ownerName", e.target.value)} autoFocus />
            </Field>
            <Field label="Owner's username" error={error?.field("username")}>
              <Input value={s.ownerUsername} onChange={(e) => set("ownerUsername", e.target.value.toLowerCase())} className="font-mono" />
            </Field>
            <Field label="Owner's email" hint="The setup link is emailed here" error={error?.field("ownerEmail")}>
              <Input type="email" value={s.ownerEmail} onChange={(e) => set("ownerEmail", e.target.value)} />
            </Field>
            <Field label="Owner's phone">
              <Input value={s.ownerPhone} onChange={(e) => set("ownerPhone", e.target.value)} inputMode="tel" />
            </Field>
            <Field label="Internal notes" className="sm:col-span-2">
              <Textarea value={s.notes} onChange={(e) => set("notes", e.target.value)} rows={2} />
            </Field>
            <div className="rounded-xl bg-deep p-4 text-sm text-muted sm:col-span-2">
              <p className="font-semibold text-ink">{s.name || "New shop"}</p>
              <p>
                {type?.name ?? "—"} · {plan?.name ?? "—"} ({s.cycle === "YEAR" ? "yearly" : "monthly"}) · {s.trialDays || 0}-day trial ·{" "}
                {plural(s.branches.split("\n").filter((b) => b.trim()).length, "branch", "branches")}
              </p>
            </div>
          </div>
        )}

        <div className="mt-6 flex justify-between gap-2 border-t border-line pt-4">
          <Button variant="ghost" onClick={() => setStep((x) => Math.max(0, x - 1))} disabled={step === 0}>
            Back
          </Button>
          {step < STEPS.length - 1 ? (
            <Button onClick={() => setStep((x) => x + 1)} disabled={!canNext}>
              Next
            </Button>
          ) : (
            <Button onClick={() => create.mutate()} loading={create.isPending} disabled={!canNext}>
              Create shop
            </Button>
          )}
        </div>
      </Panel>
    </>
  );
}
