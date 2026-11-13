"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { ProductPicker, type PickedProduct } from "@/components/product-picker";
import { AmountInput, Input, Select, Textarea } from "@/components/ui/fields";
import { Table, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { date, money, qty as fmtQty } from "@/lib/format";
import { lineAmount } from "@/lib/money";

export type Product = Schemas["ProductView"];

/** One line of a stock or purchase document while it is being entered. */
export type DocLine = {
  key: string;
  product: Product;
  unitId: number;
  qty: string;
  cost: string;
  price: string;
  bonus: string;
  discount: string;
  batchNo: string;
  expiry: string;
  batchId: number | null;
  /** IMEIs / serials, one per line. */
  serials: string;
  /** The purchase-order line this receipt line fills. */
  poLineId?: number | null;
  /** Most that may be entered (open qty of an order line, qty left on a bill). */
  maxQty?: string | null;
};

/** A new line for a product already loaded (pre-filled from an order or a bill). */
export function newLine(product: Product, patch: Partial<DocLine> = {}): DocLine {
  counter += 1;
  return {
    key: `l${counter}`,
    product,
    unitId: product.unitId,
    qty: product.kind === "SERIAL" ? "" : "1",
    cost: product.costAvg ?? "",
    price: product.price,
    bonus: "",
    discount: "",
    batchNo: "",
    expiry: "",
    batchId: null,
    serials: "",
    ...patch,
  };
}

export type LineConfig = {
  /** Pack units (carton, box) as well as the base unit. */
  unit?: boolean;
  cost?: boolean;
  price?: boolean;
  bonus?: boolean;
  discount?: boolean;
  /** "new" = type batch no. and expiry (receipts); "pick" = choose an existing batch (out of stock). */
  batch?: "new" | "pick";
  /** "new" = scan new IMEIs (receipts); "existing" = scan IMEIs in stock. */
  serials?: "new" | "existing";
  qtyLabel?: string;
  /** Product kinds that may be added. */
  kinds?: string[];
};

let counter = 0;

/** Lines of a document with add / change / remove. */
export function useDocLines() {
  const [lines, setLines] = React.useState<DocLine[]>([]);
  const client = useQueryClient();
  const add = React.useCallback(
    async (picked: PickedProduct) => {
      try {
        const product = await client.fetchQuery({
          queryKey: ["product", picked.id],
          queryFn: () => api<Product>(`/products/${picked.id}`),
          staleTime: 60_000,
        });
        setLines((current) => {
          const existing = current.find((l) => l.product.id === product.id && product.kind === "STANDARD");
          if (existing) {
            return current.map((l) => (l === existing ? { ...l, qty: addOne(l.qty) } : l));
          }
          return [...current, newLine(product)];
        });
      }
      catch (e) {
        toast.error(errorMessage(e));
      }
    },
    [client],
  );
  const update = React.useCallback((key: string, patch: Partial<DocLine>) => {
    setLines((current) => current.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  }, []);
  const remove = React.useCallback((key: string) => setLines((current) => current.filter((l) => l.key !== key)), []);
  const clear = React.useCallback(() => setLines([]), []);
  return { lines, add, update, remove, clear, setLines };
}

function addOne(value: string) {
  const n = Number(value || "0");
  return Number.isFinite(n) ? String(n + 1) : value;
}

/** IMEIs typed or scanned into the box, one per line (or separated by commas/spaces). */
export function serialList(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** Quantity of a line: the number of IMEIs for serial items. */
export function lineQty(line: DocLine): string {
  return line.product.kind === "SERIAL" ? String(serialList(line.serials).length) : line.qty;
}

/** Line amount (qty × cost or price, less discount) for totals shown while typing. */
export function lineValue(line: DocLine, by: "cost" | "price"): string {
  const q = lineQty(line) || "0";
  const rate = (by === "cost" ? line.cost : line.price) || "0";
  if (!/^\d+(\.\d{1,3})?$/.test(q) || !/^\d+(\.\d{1,2})?$/.test(rate)) return "0.00";
  const gross = lineAmount(q, rate);
  const disc = /^\d+(\.\d{1,2})?$/.test(line.discount) ? line.discount : "0";
  return (Number(gross) - Number(disc)).toFixed(2);
}

export function DocLines({
  lines,
  update,
  remove,
  onAdd,
  config,
  branchId,
}: {
  lines: DocLine[];
  update: (key: string, patch: Partial<DocLine>) => void;
  remove: (key: string) => void;
  onAdd: (product: PickedProduct) => void;
  config: LineConfig;
  branchId?: number | null;
}) {
  return (
    <div className="space-y-3">
      <ProductPicker onPick={onAdd} kinds={config.kinds} />
      {lines.length > 0 && (
        <Table className="min-w-[720px]">
          <thead>
            <tr>
              <Th>Item</Th>
              {config.unit && <Th>Unit</Th>}
              <Th align="right">{config.qtyLabel ?? "Qty"}</Th>
              {config.bonus && <Th align="right">Bonus</Th>}
              {config.cost && <Th align="right">Cost</Th>}
              {config.price && <Th align="right">Price</Th>}
              {config.discount && <Th align="right">Discount</Th>}
              {config.batch && <Th>Batch</Th>}
              {(config.cost || config.price) && <Th align="right">Amount</Th>}
              <Th>
                <span className="sr-only">Remove</span>
              </Th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => (
              <LineRow key={line.key} line={line} update={update} remove={remove} config={config} branchId={branchId} />
            ))}
          </tbody>
        </Table>
      )}
    </div>
  );
}

function LineRow({
  line,
  update,
  remove,
  config,
  branchId,
}: {
  line: DocLine;
  update: (key: string, patch: Partial<DocLine>) => void;
  remove: (key: string) => void;
  config: LineConfig;
  branchId?: number | null;
}) {
  const p = line.product;
  const serial = p.kind === "SERIAL" && config.serials;
  const batch = p.kind === "BATCH" && config.batch;
  const batches = useQuery({
    queryKey: ["batches", p.id, branchId ?? null],
    queryFn: () => api<Schemas["BatchStock"][]>(`/batches?productId=${p.id}${branchId ? `&branchId=${branchId}` : ""}`),
    enabled: batch === "pick",
  });
  const units = [{ unitId: p.unitId, unitName: p.unitName, factor: "1" }, ...(p.packUnits ?? [])];
  return (
    <tr className="align-top">
      <Td>
        <p className="font-medium">{p.name}</p>
        <p className="text-xs text-faint">{p.sku}</p>
        {serial && (
          <Textarea
            value={line.serials}
            onChange={(e) => update(line.key, { serials: e.target.value })}
            placeholder={config.serials === "new" ? "Scan each new IMEI / serial" : "Scan the IMEI / serial of each unit"}
            aria-label={`IMEIs for ${p.name}`}
            className="mt-2 min-h-16 font-mono text-xs"
          />
        )}
      </Td>
      {config.unit && (
        <Td>
          <Select
            value={line.unitId}
            onChange={(e) => {
              const unitId = Number(e.target.value);
              const pack = p.packUnits?.find((u) => u.unitId === unitId);
              update(line.key, {
                unitId,
                price: pack?.price ?? p.price,
              });
            }}
            aria-label={`Unit for ${p.name}`}
            className="w-28"
            disabled={p.kind === "SERIAL"}
          >
            {units.map((u) => (
              <option key={u.unitId} value={u.unitId}>
                {u.unitName}
                {u.factor !== "1" ? ` (${fmtQty(u.factor)})` : ""}
              </option>
            ))}
          </Select>
        </Td>
      )}
      <Td align="right">
        {serial ? (
          <span className="font-mono">{serialList(line.serials).length}</span>
        ) : (
          <AmountInput
            value={line.qty}
            onChange={(e) => update(line.key, { qty: e.target.value })}
            aria-label={`Quantity of ${p.name}`}
            className="ml-auto w-24"
          />
        )}
      </Td>
      {config.bonus && (
        <Td align="right">
          <AmountInput value={line.bonus} onChange={(e) => update(line.key, { bonus: e.target.value })} aria-label={`Bonus of ${p.name}`} className="ml-auto w-20" />
        </Td>
      )}
      {config.cost && (
        <Td align="right">
          <AmountInput value={line.cost} onChange={(e) => update(line.key, { cost: e.target.value })} aria-label={`Cost of ${p.name}`} className="ml-auto w-28" />
        </Td>
      )}
      {config.price && (
        <Td align="right">
          <AmountInput value={line.price} onChange={(e) => update(line.key, { price: e.target.value })} aria-label={`Price of ${p.name}`} className="ml-auto w-28" />
        </Td>
      )}
      {config.discount && (
        <Td align="right">
          <AmountInput value={line.discount} onChange={(e) => update(line.key, { discount: e.target.value })} aria-label={`Discount on ${p.name}`} className="ml-auto w-24" />
        </Td>
      )}
      {config.batch && (
        <Td>
          {batch === "new" && (
            <div className="flex w-56 gap-1">
              <Input value={line.batchNo} onChange={(e) => update(line.key, { batchNo: e.target.value })} placeholder="Batch no." aria-label={`Batch of ${p.name}`} />
              <Input type="date" value={line.expiry} onChange={(e) => update(line.key, { expiry: e.target.value })} aria-label={`Expiry of ${p.name}`} />
            </div>
          )}
          {batch === "pick" && (
            <Select
              value={line.batchId ?? ""}
              onChange={(e) => update(line.key, { batchId: e.target.value ? Number(e.target.value) : null })}
              aria-label={`Batch of ${p.name}`}
              className="w-56"
            >
              <option value="">Choose batch</option>
              {batches.data?.map((b) => (
                <option key={`${b.batchId}-${b.branchId}`} value={b.batchId}>
                  {b.batchNo} · exp {date(b.expiry)} · {fmtQty(b.qty)}
                  {b.expired ? " (expired)" : ""}
                </option>
              ))}
            </Select>
          )}
          {!batch && <span className="text-faint">—</span>}
        </Td>
      )}
      {(config.cost || config.price) && <Td align="right">{money(lineValue(line, config.cost ? "cost" : "price"))}</Td>}
      <Td>
        <button className="rounded-lg p-1.5 text-faint hover:bg-raised hover:text-bad" onClick={() => remove(line.key)} aria-label={`Remove ${p.name}`}>
          <Trash2 className="size-4" />
        </button>
      </Td>
    </tr>
  );
}
