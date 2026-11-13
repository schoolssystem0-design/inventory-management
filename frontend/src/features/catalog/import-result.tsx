import { AlertTriangle, CheckCircle2 } from "lucide-react";
import { StatCard, Table, Td, Th } from "@/components/ui/layout";
import type { Schemas } from "@/lib/api/client";
import { plural } from "@/lib/format";

/** The dry-run report of a product import: counts, what will be created, and every row problem. */
export function ImportResultView({ result }: { result: Schemas["ImportResult"] }) {
  const ok = result.errors.length === 0;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Rows" value={result.rows.toLocaleString()} />
        <StatCard label="New products" value={result.toCreate.toLocaleString()} tone="aqua" />
        <StatCard label="Updated products" value={result.toUpdate.toLocaleString()} />
      </div>
      {ok ? (
        <p className="flex items-center gap-2 text-sm text-good">
          <CheckCircle2 className="size-4" aria-hidden /> No problems found.
        </p>
      ) : (
        <p className="flex items-center gap-2 text-sm text-warn">
          <AlertTriangle className="size-4" aria-hidden /> {plural(result.errors.length, "problem")} to fix in the file. Nothing will be
          saved until all are fixed.
        </p>
      )}
      {(result.newCategories.length > 0 || result.newBrands.length > 0 || result.newUnits.length > 0) && (
        <div className="space-y-1 text-sm text-muted">
          {result.newCategories.length > 0 && <p>New categories: {result.newCategories.join(", ")}</p>}
          {result.newBrands.length > 0 && <p>New brands: {result.newBrands.join(", ")}</p>}
          {result.newUnits.length > 0 && <p>New units: {result.newUnits.join(", ")}</p>}
        </div>
      )}
      {result.ignoredColumns.length > 0 && (
        <p className="text-xs text-faint">Columns not used: {result.ignoredColumns.join(", ")}</p>
      )}
      {!ok && (
        <div className="glass overflow-hidden">
          <Table className="min-w-0">
            <thead>
              <tr>
                <Th className="w-20">Row</Th>
                <Th className="w-40">Column</Th>
                <Th>Problem</Th>
              </tr>
            </thead>
            <tbody>
              {result.errors.map((e, i) => (
                <tr key={i}>
                  <Td className="font-mono">{e.row || "—"}</Td>
                  <Td>{e.column || "—"}</Td>
                  <Td className="text-bad">{e.message}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </div>
      )}
    </div>
  );
}
