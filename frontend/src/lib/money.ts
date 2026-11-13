/**
 * Exact money and quantity maths for the till (docs/architecture/CODING-STANDARDS.md §3), the same rules as the server's SaleMath:
 * money is kept as whole paisa and quantities as thousandths in BigInt, rounding HALF_UP. Never parseFloat money.
 * The shared vectors in docs/test-vectors/money.json check both sides.
 */



function scaled(value: string | null | undefined, scale: number): bigint {
  const text = (value ?? "0").toString().replace(/,/g, "").trim() || "0";
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = (negative ? text.slice(1) : text).split(".");
  const digits = (whole || "0") + (fraction + "0".repeat(scale)).slice(0, scale);
  const result = BigInt(digits);
  return negative ? -result : result;
}

function unscaled(value: bigint, scale: number): string {
  const negative = value < BigInt(0);
  const abs = negative ? -value : value;
  const text = abs.toString().padStart(scale + 1, "0");
  const whole = text.slice(0, text.length - scale);
  const fraction = text.slice(text.length - scale);
  return (negative ? "-" : "") + whole + (scale ? "." + fraction : "");
}

/** a / b rounded half up (away from zero). */
function divRound(a: bigint, b: bigint): bigint {
  if (b === BigInt(0)) return BigInt(0);
  const negative = a < BigInt(0) !== b < BigInt(0);
  const absA = a < BigInt(0) ? -a : a;
  const absB = b < BigInt(0) ? -b : b;
  const q = (absA * BigInt(2) + absB) / (absB * BigInt(2));
  return negative ? -q : q;
}

/** Whole paisa from "1250.50". */
export const toPaisa = (value: string | null | undefined) => scaled(value, 2);
/** "1250.50" from whole paisa. */
export const fromPaisa = (value: bigint) => unscaled(value, 2);
/** Thousandths from "2.5". */
export const toMilli = (value: string | null | undefined) => scaled(value, 3);
export const fromMilli = (value: bigint) => unscaled(value, 3);

export const addMoney = (...values: (string | null | undefined)[]) =>
  fromPaisa(values.reduce((sum, v) => sum + toPaisa(v), BigInt(0)));
export const subMoney = (a: string, b: string) => fromPaisa(toPaisa(a) - toPaisa(b));
export const cmpMoney = (a: string, b: string) => {
  const d = toPaisa(a) - toPaisa(b);
  return d === BigInt(0) ? 0 : d > BigInt(0) ? 1 : -1;
};
export const isZero = (a: string | null | undefined) => toPaisa(a) === BigInt(0);
export const maxMoney = (a: string, b: string) => (cmpMoney(a, b) >= 0 ? a : b);
export const minMoney = (a: string, b: string) => (cmpMoney(a, b) <= 0 ? a : b);

/** qty × price to 2 decimals (1.250 kg × 180.00 = 225.00). */
export function lineAmount(qty: string, price: string): string {
  return fromPaisa(divRound(toMilli(qty) * toPaisa(price), BigInt(1000)));
}

/** qty × factor (pack units to base units), 3 decimals. */
export function multiplyQty(qty: string, factor: string): string {
  return fromMilli(divRound(toMilli(qty) * toMilli(factor), BigInt(1000)));
}

/** A percentage (up to 4 decimals) of an amount, 2 decimals. */
export function percentOf(amount: string, percent: string): string {
  return fromPaisa(divRound(toPaisa(amount) * scaled(percent, 4), BigInt(1000000)));
}

/** Percentage of part in whole, 2 decimals ("0.00" when whole is 0). */
export function percent(part: string, whole: string): string {
  const w = toPaisa(whole);
  if (w === BigInt(0)) return "0.00";
  return fromPaisa(divRound(toPaisa(part) * BigInt(10000), w));
}

/** Bill discount spread over the lines in proportion; the last line with an amount takes the remainder. */
export function spread(amounts: string[], discount: string): string[] {
  const values = amounts.map(toPaisa);
  const total = values.reduce((s, v) => s + v, BigInt(0));
  const d = toPaisa(discount);
  if (d === BigInt(0) || total === BigInt(0)) return amounts.map(() => "0.00");
  let last = -1;
  values.forEach((v, i) => {
    if (v > BigInt(0)) last = i;
  });
  let given = BigInt(0);
  return values.map((v, i) => {
    const share = i === last ? d - given : divRound(d * v, total);
    given += share;
    return fromPaisa(share);
  });
}

/** Tax on a line: added on top (EXCLUSIVE) or contained in the amount (INCLUSIVE); rate like "17" or "17.5". */
export function tax(mode: string, net: string, rate: string | null | undefined): string {
  const r = scaled(rate ?? "0", 4);
  if (!mode || mode === "NONE" || r === BigInt(0)) return "0.00";
  const n = toPaisa(net);
  if (mode === "INCLUSIVE") return fromPaisa(divRound(n * r, BigInt(1000000) + r));
  return fromPaisa(divRound(n * r, BigInt(1000000)));
}

/** What the customer pays for a line: net plus tax when tax is added on top. */
export function lineTotal(mode: string, net: string, lineTax: string): string {
  return mode === "EXCLUSIVE" ? addMoney(net, lineTax) : fromPaisa(toPaisa(net));
}

/** Weighted average cost after receiving (stock below zero counts as zero). */
export function averageCost(onHand: string, currentAvg: string, qtyIn: string, unitCost: string): string {
  const held = toMilli(onHand) < BigInt(0) ? BigInt(0) : toMilli(onHand);
  if (held === BigInt(0)) return fromPaisa(toPaisa(unitCost));
  const value = held * toPaisa(currentAvg) + toMilli(qtyIn) * toPaisa(unitCost);
  return fromPaisa(divRound(value, held + toMilli(qtyIn)));
}

/** What the customer still owes on an invoice before a return. */
export function dueBefore(total: string, returned: string, paid: string): string {
  const due = toPaisa(total) - toPaisa(returned) - toPaisa(paid);
  return fromPaisa(due > BigInt(0) ? due : BigInt(0));
}

/** Most that can be refunded: the return first clears what is owed. */
export function maxRefund(total: string, returned: string, paid: string, returnValue: string): string {
  const rest = toPaisa(returnValue) - toPaisa(dueBefore(total, returned, paid));
  return fromPaisa(rest > BigInt(0) ? rest : BigInt(0));
}

/** Value of returning qty of a line (running totals, so parts add up to the line total). */
export function returnValue(lineTotalValue: string, lineQty: string, alreadyReturned: string, qty: string): string {
  const total = toPaisa(lineTotalValue);
  const lq = toMilli(lineQty);
  const after = toMilli(alreadyReturned) + toMilli(qty);
  const upToAfter = after >= lq ? total : divRound(total * after, lq);
  const upToBefore = divRound(total * toMilli(alreadyReturned), lq);
  return fromPaisa(upToAfter - upToBefore);
}

/** Suggested order quantity: max(30-day sales, 2 × reorder level) − on hand − on order, at least 0, whole units. */
export function reorderSuggestion(reorderLevel: string, sold30: string, onHand: string, onOrder: string): string {
  const level = toMilli(reorderLevel) * BigInt(2);
  const sold = toMilli(sold30);
  const target = sold > level ? sold : level;
  const held = toMilli(onHand) < BigInt(0) ? BigInt(0) : toMilli(onHand);
  const suggest = target - held - toMilli(onOrder);
  if (suggest <= BigInt(0)) return "0";
  const thousand = BigInt(1000);
  return ((suggest + thousand - BigInt(1)) / thousand).toString();
}

/** Valid money text with at most 2 decimals and no sign. */
export function isMoney(text: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(text.replace(/,/g, "").trim());
}

/** Valid quantity text with at most 3 decimals (or whole only). */
export function isQty(text: string, decimals = true): boolean {
  return (decimals ? /^\d+(\.\d{1,3})?$/ : /^\d+$/).test(text.trim());
}

export const ZERO = "0.00";
