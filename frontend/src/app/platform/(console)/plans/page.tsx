"use client";

import { useQuery } from "@tanstack/react-query";
import { PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";

/** BIL-01, BIL-02: plans, prices and limits. */
export default function PlansPage() {
  const plans = useQuery({ queryKey: ["plans"], queryFn: () => api<Schemas["PlanView"][]>("/platform/plans") });
  const limit = (n?: number | null) => (n == null ? "Unlimited" : n.toLocaleString());
  return (
    <>
      <PageHeader title="Plans" description="Prices and limits for shops" />
      <Panel padded={false}>
        {plans.isPending ? (
          <TableSkeleton />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th />
                {plans.data?.map((p) => (
                  <Th key={p.key} align="right">
                    {p.name}
                  </Th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[
                ["Monthly", (p: Schemas["PlanView"]) => money(p.priceMonth)],
                ["Yearly", (p: Schemas["PlanView"]) => money(p.priceYear)],
                ["Setup fee", (p: Schemas["PlanView"]) => money(p.setupFee)],
                ["Users", (p: Schemas["PlanView"]) => limit(p.maxUsers)],
                ["Branches", (p: Schemas["PlanView"]) => limit(p.maxBranches)],
                ["Products", (p: Schemas["PlanView"]) => limit(p.maxProducts)],
                ["Tills", (p: Schemas["PlanView"]) => limit(p.maxTills)],
                ["SMS a month", (p: Schemas["PlanView"]) => p.smsPerMonth.toLocaleString()],
                ["Storage", (p: Schemas["PlanView"]) => `${(p.storageMb / 1024).toFixed(0)} GB`],
                ["Features", (p: Schemas["PlanView"]) => (p.phase1ModulesOnly ? "First-release features" : "All features")],
                ["Custom domain", (p: Schemas["PlanView"]) => (p.customDomain ? "Yes" : "—")],
              ].map(([name, value]) => (
                <tr key={name as string}>
                  <Td className="text-muted">{name as string}</Td>
                  {plans.data?.map((p) => (
                    <Td key={p.key} align="right">
                      {(value as (p: Schemas["PlanView"]) => string)(p)}
                    </Td>
                  ))}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
    </>
  );
}
