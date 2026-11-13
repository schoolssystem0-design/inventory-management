"use client";

import { useQuery } from "@tanstack/react-query";
import { Check, Minus } from "lucide-react";
import * as React from "react";
import { PageHeader, Panel, TableSkeleton } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { plural } from "@/lib/format";

/** What each role may do (ACC-01). Roles you design yourself arrive in a later release (ACC-02). */
export default function RolesPage() {
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api<Schemas["RoleView"][]>("/roles") });
  const catalogue = useQuery({ queryKey: ["permissions"], queryFn: () => api<Schemas["PermissionGroup"][]>("/permissions") });
  const [selected, setSelected] = React.useState<number | null>(null);
  const role = roles.data?.find((r) => r.id === selected) ?? roles.data?.[0];

  return (
    <>
      <PageHeader title="Roles" description="What each kind of user may do. The owner can always do everything." />
      {roles.isPending || catalogue.isPending ? (
        <TableSkeleton />
      ) : (
        <div className="grid gap-5 lg:grid-cols-3">
          <Panel padded={false}>
            <ul className="p-2">
              {roles.data?.map((r) => (
                <li key={r.id}>
                  <button
                    onClick={() => setSelected(r.id)}
                    aria-current={role?.id === r.id}
                    className={`w-full rounded-xl px-3 py-2.5 text-left ${role?.id === r.id ? "bg-raised" : "hover:bg-raised/50"}`}
                  >
                    <p className="text-sm font-semibold">{r.name}</p>
                    <p className="text-xs text-faint">{plural(r.activeUsers, "user")}</p>
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
          {role && (
            <Panel title={role.name} description={role.description} className="lg:col-span-2">
              <div className="space-y-5">
                {catalogue.data?.map((group) => (
                  <div key={group.group}>
                    <p className="mb-2 text-xs font-semibold tracking-wider text-faint uppercase">{group.group}</p>
                    <ul className="grid gap-1 sm:grid-cols-2">
                      {group.permissions.map((p) => {
                        const has = role.permissions.includes(p.code);
                        return (
                          <li key={p.code} className={`flex items-center gap-2 text-sm ${has ? "text-ink" : "text-faint"}`}>
                            {has ? <Check className="size-4 text-good" aria-label="Allowed" /> : <Minus className="size-4" aria-label="Not allowed" />}
                            {p.label}
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                ))}
              </div>
            </Panel>
          )}
        </div>
      )}
    </>
  );
}
