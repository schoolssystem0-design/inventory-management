"use client";

import { useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Download, FileSpreadsheet, Upload } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ImportResultView } from "@/features/catalog/import-result";
import { Button } from "@/components/ui/button";
import { PageHeader, Panel } from "@/components/ui/layout";
import { ApiError, api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { plural } from "@/lib/format";
import { ShopLink, useShopHref } from "@/lib/shop-context";

type Result = Schemas["ImportResult"];

/** CAT-06: upload → see every problem (nothing saved) → fix in Excel → upload again → save all rows. */
export default function ImportPage() {
  const toHref = useShopHref();
  const client = useQueryClient();
  const [file, setFile] = React.useState<File | null>(null);
  const [result, setResult] = React.useState<Result | null>(null);
  const [busy, setBusy] = React.useState<"check" | "save" | null>(null);
  const [done, setDone] = React.useState<Result | null>(null);

  async function send(dryRun: boolean) {
    if (!file) return;
    setBusy(dryRun ? "check" : "save");
    const form = new FormData();
    form.append("file", file);
    try {
      const res = await api<Result>(`/products/import?dryRun=${dryRun}`, { method: "POST", form });
      if (dryRun) setResult(res);
      else {
        setDone(res);
        setResult(null);
        setFile(null);
        client.invalidateQueries({ queryKey: ["products"] });
        client.invalidateQueries({ queryKey: ["shop-context"] });
      }
    } catch (e) {
      if (e instanceof ApiError && Array.isArray(e.extra.errors)) {
        setResult({ committed: false, rows: 0, toCreate: 0, toUpdate: 0, errors: e.extra.errors as Result["errors"], newCategories: [], newBrands: [], newUnits: [], ignoredColumns: [] });
      }
      toast.error(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function template() {
    try {
      saveBlob(await apiBlob("/products/import/template"), "products-template.xlsx");
    } catch (e) {
      toast.error(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader
        title="Import products"
        description="Add or update many products from an Excel (.xlsx) or CSV file"
        back={{ href: toHref("/products"), label: "Products" }}
        actions={
          <Button variant="secondary" onClick={template}>
            <Download /> Download template
          </Button>
        }
      />

      {done && (
        <div role="status" className="glass mb-5 flex items-start gap-3 p-4">
          <CheckCircle2 className="mt-0.5 size-5 text-good" aria-hidden />
          <div className="text-sm">
            <p className="font-semibold">Import finished</p>
            <p className="text-muted">
              {plural(done.toCreate, "product")} added and {plural(done.toUpdate, "product")} updated.{" "}
              <ShopLink href="/products" className="text-aqua hover:underline">
                See products
              </ShopLink>
            </p>
          </div>
        </div>
      )}

      <Panel title="1. Choose the file">
        <label className="flex cursor-pointer flex-col items-center gap-3 rounded-xl border border-dashed border-line-strong px-6 py-10 text-center hover:bg-raised/40">
          <FileSpreadsheet className="size-8 text-aqua" aria-hidden />
          <span className="text-sm font-semibold">{file ? file.name : "Choose an Excel or CSV file"}</span>
          <span className="text-xs text-faint">The first row must be the column headers. Up to 50,000 rows.</span>
          <input
            type="file"
            accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
            className="sr-only"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setResult(null);
              setDone(null);
            }}
          />
        </label>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button onClick={() => send(true)} disabled={!file} loading={busy === "check"}>
            <Upload /> Check the file
          </Button>
        </div>
        <p className="mt-3 text-xs text-faint">
          Checking saves nothing. A SKU that already exists updates that product; categories, brands and units that don&apos;t exist yet are
          created.
        </p>
      </Panel>

      {result && (
        <Panel title="2. Check the result" className="mt-5">
          <ImportResultView result={result} />
          {result.errors.length === 0 && result.rows > 0 && (
            <Button className="mt-5" onClick={() => send(false)} loading={busy === "save"}>
              Save {plural(result.rows, "row")}
            </Button>
          )}
        </Panel>
      )}
    </>
  );
}
