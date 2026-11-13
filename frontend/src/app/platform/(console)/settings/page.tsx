"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { toast } from "sonner";
import { useCapability } from "@/components/shell/platform-shell";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { PageHeader, Panel, Skeleton } from "@/components/ui/layout";
import { api, errorMessage } from "@/lib/api/client";

const FIELDS: { key: string; label: string; hint?: string }[] = [
  { key: "company_name", label: "Company name" },
  { key: "product_name", label: "Product name" },
  { key: "support_phone", label: "Support phone", hint: "Shown to shops" },
  { key: "support_email", label: "Support email" },
  { key: "trial_days", label: "Free trial (days)", hint: "Default for new shops" },
  { key: "grace_days", label: "Grace days", hint: "After the due date, before read-only" },
  { key: "setup_link_hours", label: "Setup link valid (hours)" },
  { key: "terms_version", label: "Terms version" },
];

/** PLT-24: platform settings. */
export default function PlatformSettingsPage() {
  const cap = useCapability();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["platform-settings"], queryFn: () => api<Record<string, string>>("/platform/settings") });
  const [edited, setValues] = React.useState<Record<string, string>>({});
  const values = { ...settings.data, ...edited };
  const save = useMutation({
    mutationFn: () => {
      const changed = Object.fromEntries(Object.entries(values).filter(([k, v]) => settings.data?.[k] !== v));
      return api<Record<string, string>>("/platform/settings", { method: "PATCH", body: changed });
    },
    onSuccess: (s) => {
      client.setQueryData(["platform-settings"], s);
      toast.success("Settings saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const edit = cap("platform.admin");
  return (
    <>
      <PageHeader title="Platform settings" />
      {settings.isPending ? (
        <Skeleton className="h-64" />
      ) : (
        <Panel>
          <fieldset disabled={!edit} className="grid gap-4 sm:grid-cols-2">
            {FIELDS.map((f) => (
              <Field key={f.key} label={f.label} hint={f.hint}>
                <Input value={values[f.key] ?? ""} onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} />
              </Field>
            ))}
          </fieldset>
          {edit && (
            <Button className="mt-5" onClick={() => save.mutate()} loading={save.isPending}>
              Save
            </Button>
          )}
        </Panel>
      )}
    </>
  );
}
