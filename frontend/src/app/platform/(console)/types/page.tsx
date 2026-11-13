"use client";

import { useQuery } from "@tanstack/react-query";
import { PageHeader, Panel, TableSkeleton } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";

/** PLT-19: shop type templates. Editing them arrives in a later release (PLT-20, PLT-21). */
export default function TypesPage() {
  const types = useQuery({ queryKey: ["shop-types"], queryFn: () => api<Schemas["ShopTypeView"][]>("/platform/shop-types") });
  const catalogue = useQuery({ queryKey: ["module-catalogue"], queryFn: () => api<{ key: string; label: string }[]>("/platform/modules") });
  const name = (k: string) => catalogue.data?.find((m) => m.key === k)?.label ?? k;
  return (
    <>
      <PageHeader title="Shop types" description="What each kind of shop gets when it is created" />
      {types.isPending ? (
        <TableSkeleton />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {types.data?.map((t) => {
            const template = (() => {
              try {
                return JSON.parse(t.template) as { categories?: string[]; units?: { name: string }[] };
              } catch {
                return {};
              }
            })();
            return (
              <Panel key={t.key} title={t.name} description={t.description}>
                <div className="space-y-3 text-sm">
                  <p>
                    <span className="text-faint">On by default: </span>
                    {t.defaultModules.map(name).join(", ") || "core features only"}
                  </p>
                  <p>
                    <span className="text-faint">Optional: </span>
                    {t.offeredModules.map(name).join(", ")}
                  </p>
                  <p>
                    <span className="text-faint">Categories: </span>
                    {template.categories?.join(", ")}
                  </p>
                  <p>
                    <span className="text-faint">Units: </span>
                    {template.units?.map((u) => u.name).join(", ")}
                  </p>
                </div>
              </Panel>
            );
          })}
        </div>
      )}
    </>
  );
}
