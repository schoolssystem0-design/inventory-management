/**
 * Offers and coupons at the till (SUP-05, XSL-02). The same rules as the server's PromotionEngine, checked against
 * the same numbers (docs/test-vectors/promotions.json), so the total on screen is the total the server books.
 *
 * Item offers apply only to a line in the base unit, at the list price, with nothing typed in its discount; the best
 * offer per line wins (the older offer on a tie). A bill offer applies to the bill after line discounts when it
 * reaches the minimum and no bill discount was typed. Coupon offers apply only when their code is entered.
 */
import { addMoney, cmpMoney, fromMilli, lineAmount, minMoney, percentOf, subMoney, toMilli, ZERO } from "@/lib/money";

export type Offer = {
  id: number;
  name?: string;
  kind: string;
  value?: string | null;
  buyQty?: string | null;
  getQty?: string | null;
  minAmount?: string | null;
  scope?: string | null;
  categoryIds?: number[] | null;
  productIds?: number[] | null;
  startsOn?: string | null;
  endsOn?: string | null;
  /** ISO days, 1 = Monday. */
  days?: number[] | null;
  /** "17:00". */
  fromTime?: string | null;
  toTime?: string | null;
  couponCode?: string | null;
  maxUses?: number | null;
  uses?: number | null;
  active?: boolean | null;
};

export type OfferLine = { productId: number; categoryId: number | null; qty: string; price: string; eligible: boolean };

export type Applied = { offerId: number | null; name: string | null; amount: string };

export const NONE: Applied = { offerId: null, name: null, amount: ZERO };

const LINE_KINDS = ["PERCENT", "AMOUNT", "BUY_GET", "MULTI_PRICE"];
const BILL_KINDS = ["BILL_PERCENT", "BILL_AMOUNT"];

/** Whole groups of `size` in `qty` (rounded down). */
function groups(qty: string, size: string): bigint {
  const s = toMilli(size);
  return s === BigInt(0) ? BigInt(0) : toMilli(qty) / s;
}

/** "HH:MM" → minutes after midnight. */
function minutes(t: string): number {
  const [h, m] = t.split(":");
  return Number(h) * 60 + Number(m);
}

/** Running at this date ("2026-11-13") and time ("18:30"): active, dates, days of the week, hours, use limit. */
export function running(o: Offer, date: string, time: string): boolean {
  if (o.active === false) return false;
  if ((o.startsOn && date < o.startsOn) || (o.endsOn && date > o.endsOn)) return false;
  if (o.days && o.days.length) {
    const [y, mo, d] = date.split("-").map(Number);
    const js = new Date(Date.UTC(y, mo - 1, d)).getUTCDay();
    if (!o.days.includes(js === 0 ? 7 : js)) return false;
  }
  if (o.fromTime && o.toTime) {
    const t = minutes(time);
    const from = minutes(o.fromTime);
    const to = minutes(o.toTime);
    const inside = from < to ? t >= from && t < to : t >= from || t < to;
    if (!inside) return false;
  }
  return o.maxUses == null || (o.uses ?? 0) < o.maxUses;
}

function covers(o: Offer, line: OfferLine): boolean {
  if (o.scope === "CATEGORY") return line.categoryId != null && (o.categoryIds ?? []).includes(line.categoryId);
  if (o.scope === "PRODUCTS") return (o.productIds ?? []).includes(line.productId);
  return true;
}

/** What an item offer takes off a line (never more than the line). */
export function lineOffer(o: Offer, line: OfferLine): string {
  const gross = lineAmount(line.qty, line.price);
  let off = ZERO;
  switch (o.kind) {
    case "PERCENT":
      off = percentOf(gross, o.value ?? "0");
      break;
    case "AMOUNT":
      off = lineAmount(line.qty, o.value ?? "0");
      break;
    case "BUY_GET": {
      const group = fromMilli(toMilli(o.buyQty) + toMilli(o.getQty));
      const free = fromMilli(groups(line.qty, group) * toMilli(o.getQty));
      off = lineAmount(free, line.price);
      break;
    }
    case "MULTI_PRICE": {
      const n = groups(line.qty, o.buyQty ?? "0");
      const saving = subMoney(lineAmount(o.buyQty ?? "0", line.price), o.value ?? "0");
      off = cmpMoney(saving, ZERO) <= 0 ? ZERO : lineAmount(fromMilli(n * BigInt(1000)), saving);
      break;
    }
  }
  const capped = minMoney(off, gross);
  return cmpMoney(capped, ZERO) < 0 ? ZERO : capped;
}

/** What a bill offer takes off the bill (after line discounts), or nothing below its minimum. */
export function billOffer(o: Offer, bill: string): string {
  if (cmpMoney(bill, o.minAmount ?? "0") < 0 || cmpMoney(bill, ZERO) <= 0) return ZERO;
  const off = o.kind === "BILL_PERCENT" ? percentOf(bill, o.value ?? "0") : addMoney(o.value ?? "0");
  return minMoney(off, bill);
}

export type OfferResult = { lines: Applied[]; bill: Applied; couponUsed: boolean };

/**
 * @param offers running offers; coupon offers are used only when `coupon` matches their code
 * @param typedDiscounts discount typed on each line (to work out the bill after line discounts)
 */
export function applyOffers(offers: Offer[], lines: OfferLine[], typedDiscounts: string[], billTyped: boolean, coupon: string | null): OfferResult {
  const code = coupon && coupon.trim() ? coupon.trim().toUpperCase() : null;
  const usable = offers.filter((o) => !o.couponCode || o.couponCode.trim().toUpperCase() === code);
  let couponUsed = false;
  let afterLine = ZERO;
  const applied = lines.map((line, i) => {
    let best: Applied = NONE;
    let bestOffer: Offer | null = null;
    if (line.eligible) {
      for (const o of usable) {
        if (!LINE_KINDS.includes(o.kind) || !covers(o, line)) continue;
        const amount = lineOffer(o, line);
        const c = cmpMoney(amount, best.amount);
        if (c > 0 || (cmpMoney(amount, ZERO) > 0 && c === 0 && best.offerId != null && o.id < best.offerId)) {
          best = { offerId: o.id, name: o.name ?? null, amount };
          bestOffer = o;
        }
      }
    }
    if (bestOffer?.couponCode) couponUsed = true;
    afterLine = addMoney(afterLine, subMoney(subMoney(lineAmount(line.qty, line.price), typedDiscounts[i] || ZERO), best.amount));
    return best;
  });
  let bill: Applied = NONE;
  let billOfferUsed: Offer | null = null;
  if (!billTyped) {
    for (const o of usable) {
      if (!BILL_KINDS.includes(o.kind)) continue;
      const amount = billOffer(o, afterLine);
      const c = cmpMoney(amount, bill.amount);
      if (c > 0 || (cmpMoney(amount, ZERO) > 0 && c === 0 && bill.offerId != null && o.id < bill.offerId)) {
        bill = { offerId: o.id, name: o.name ?? null, amount };
        billOfferUsed = o;
      }
    }
  }
  if (billOfferUsed?.couponCode) couponUsed = true;
  return { lines: applied, bill, couponUsed };
}

/** Local date and time at the till ("2026-11-13", "18:30"). */
export function nowParts(d = new Date()): { date: string; time: string } {
  const pad = (n: number) => String(n).padStart(2, "0");
  return { date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, time: `${pad(d.getHours())}:${pad(d.getMinutes())}` };
}
