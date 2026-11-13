/**
 * POS catalogue kept in memory and in IndexedDB (POS-01): the till searches and scans locally, so typing never waits
 * for the network, and a reload starts from the saved copy. The server's version string tells when to refresh.
 */
import type { Schemas } from "@/lib/api/client";
import type { CartProduct } from "./cart";
import { fromMilli, toPaisa } from "@/lib/money";
import { readScale } from "./scale";

export type PosItem = Schemas["PosItem"];
export type PosOfferView = Schemas["PosOffer"];
export type PosSettings = Schemas["PosSettings"];
/** Extras that come with the catalogue: offers running today and till settings of switched-on modules. */
export type CatalogExtras = { offers: PosOfferView[]; settings: PosSettings | null };
type Saved = { key: string; version: string; savedAt: number; items: PosItem[]; extras?: CatalogExtras };

const DB = "selectcode-pos";
const STORE = "catalog";

function openDb(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "key" });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    }
    catch {
      resolve(null);
    }
  });
}

export async function loadSaved(key: string): Promise<Saved | null> {
  const db = await openDb();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const req = db.transaction(STORE).objectStore(STORE).get(key);
      req.onsuccess = () => resolve((req.result as Saved | undefined) ?? null);
      req.onerror = () => resolve(null);
    }
    catch {
      resolve(null);
    }
  });
}

export async function save(key: string, version: string, items: PosItem[], extras: CatalogExtras): Promise<void> {
  const db = await openDb();
  if (!db) return;
  try {
    db.transaction(STORE, "readwrite").objectStore(STORE).put({ key, version, savedAt: Date.now(), items, extras } satisfies Saved);
  }
  catch {
    // Storage full or blocked: the in-memory copy still works.
  }
}

/** Lower-case, no extra spaces. */
const norm = (s: string) => s.toLowerCase().replace(/\s+/g, " ").trim();

/** Search index over the catalogue: exact codes first, then words. */
export class CatalogIndex {
  readonly items: PosItem[];
  private readonly byCode = new Map<string, { item: PosItem; unitIndex: number | null }>();
  private readonly byScale = new Map<string, PosItem>();
  private readonly text: string[];

  constructor(items: PosItem[]) {
    this.items = items;
    this.text = items.map((i) => norm([i.name, i.urduName ?? "", i.sku, i.formula ?? "", ...(i.partNumbers ?? [])].join(" ")));
    for (const item of items) {
      this.byCode.set(item.sku.toLowerCase(), { item, unitIndex: null });
      item.barcodes?.forEach((b) => this.byCode.set(b.toLowerCase(), { item, unitIndex: null }));
      item.partNumbers?.forEach((p) => {
        if (!this.byCode.has(p.toLowerCase())) this.byCode.set(p.toLowerCase(), { item, unitIndex: null });
      });
      item.units?.forEach((u, i) => {
        if (u.barcode) this.byCode.set(u.barcode.toLowerCase(), { item, unitIndex: i });
      });
      if (item.scaleCode) this.byScale.set(item.scaleCode, item);
    }
  }

  /**
   * A weighing-scale label (SUP-03): the item by its scale code and the quantity, from the weight, or from the
   * price divided by the item's price (3 decimals, rounded half up).
   */
  scale(barcode: string, mode: string): { item: PosItem; qty: string } | null {
    if (this.byScale.size === 0) return null;
    const r = readScale(barcode, mode);
    const item = r ? this.byScale.get(r.itemCode) : undefined;
    if (!r || !item) return null;
    if (mode !== "PRICE") return { item, qty: r.value };
    const price = toPaisa(item.price);
    if (price <= BigInt(0)) return null;
    const value = toPaisa(r.value);
    return { item, qty: fromMilli((value * BigInt(2000) + price) / (price * BigInt(2))) };
  }

  /** Exact barcode / SKU / part number; unitIndex is set when a pack barcode (carton) was scanned. */
  code(code: string) {
    return this.byCode.get(code.trim().toLowerCase()) ?? null;
  }

  /** Every word must appear; names that start with the query come first. */
  search(query: string, limit = 30): PosItem[] {
    const q = norm(query);
    if (!q) return [];
    const words = q.split(" ");
    const starts: PosItem[] = [];
    const rest: PosItem[] = [];
    for (let i = 0; i < this.items.length; i++) {
      const t = this.text[i];
      let ok = true;
      for (const w of words) {
        if (!t.includes(w)) {
          ok = false;
          break;
        }
      }
      if (!ok) continue;
      if (t.startsWith(q)) starts.push(this.items[i]);
      else rest.push(this.items[i]);
      if (starts.length >= limit) break;
    }
    return [...starts, ...rest].slice(0, limit);
  }
}

export function toCartProduct(item: PosItem): CartProduct {
  return {
    id: item.id,
    name: item.name,
    urduName: item.urduName,
    sku: item.sku,
    kind: item.kind,
    price: item.price,
    minPrice: item.minPrice,
    taxRate: item.taxRate,
    decimals: item.decimals,
    unitName: item.unitName,
    units: (item.units ?? []).map((u) => ({ unitId: u.unitId, name: u.name, factor: u.factor, price: u.price, barcode: u.barcode })),
    stock: item.stock,
    categoryId: item.categoryId ?? null,
    schedule: item.schedule ?? null,
  };
}
