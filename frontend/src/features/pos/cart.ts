/**
 * The POS cart and its totals. Mirrors the server's SalesService / SaleMath exactly (docs/test-vectors/money.json),
 * so the total on screen is the total the server books: line = qty × price − line discount; the bill discount is
 * spread over the lines in proportion; tax per line on the net (on top or included); change only from cash.
 */
import { addMoney, cmpMoney, fromMilli, fromPaisa, isMoney, isQty, lineAmount, lineTotal, percentOf, spread, subMoney, tax, toMilli, toPaisa, ZERO } from "@/lib/money";
import { type Applied, applyOffers, NONE, type Offer, running } from "./promotions";

export type PosUnit = { unitId: number | null; name: string; factor: string; price: string; barcode: string | null };

/** What the cart needs to know about a product (from the POS catalogue). */
export type CartProduct = {
  id: number;
  name: string;
  urduName?: string | null;
  sku: string;
  kind: string;
  price: string;
  minPrice?: string | null;
  taxRate?: string | null;
  decimals: boolean;
  unitName: string;
  units: PosUnit[];
  stock?: string | null;
  /** For category offers. */
  categoryId?: number | null;
  /** OTC, RX or CONTROLLED (pharmacy). */
  schedule?: string | null;
};

/** A prescription taken at the till (MED-08); sent with the sale. */
export type PrescriptionForm = {
  patientName: string;
  patientAge?: string | null;
  patientPhone?: string | null;
  doctorName: string;
  doctorRegNo?: string | null;
  issuedOn?: string | null;
  fileId?: number | null;
  notes?: string | null;
};

/** An offer as the till gets it from the catalogue; coupon offers have their code hidden. */
export type PosOffer = Offer & { coupon?: boolean; summary?: string | null };

/** Offers running now, for the totals. */
export type PromoContext = { offers: PosOffer[]; date: string; time: string };

export type CartLine = {
  key: string;
  product: CartProduct;
  /** null = the base unit. */
  unitId: number | null;
  unitName: string;
  qty: string;
  /** Price of one unit as charged; listPrice is the catalogue price for that unit. */
  price: string;
  listPrice: string;
  /** Amount ("50") or percent ("10%"). */
  discount: string;
  batchId: number | null;
  batchLabel?: string | null;
  serials: string[];
  /** For the kitchen, e.g. "no onions" (fast food). */
  note?: string;
};

export type Payment = { method: string; amount: string; reference?: string };

export type Cart = {
  lines: CartLine[];
  /** Amount ("100") or percent ("5%"). */
  billDiscount: string;
  customer: { id: number; name: string; phone?: string | null; balance?: string | null; creditLimit?: string | null } | null;
  /** A new customer typed at the counter; the server finds them by phone or adds them with the sale. */
  quick: { name: string; phone: string } | null;
  note: string;
  /** A coupon checked with the server: its offer applies while it is on the bill. */
  coupon?: { code: string; offerId: number; name: string } | null;
  prescription?: PrescriptionForm | null;
  /** Fast food: DINE_IN (with a table), TAKEAWAY or DELIVERY. */
  orderType?: string | null;
  tableNo?: string | null;
};

export const EMPTY_CART: Cart = { lines: [], billDiscount: "", customer: null, quick: null, note: "", coupon: null, prescription: null };

let counter = 0;
export function lineKey() {
  counter += 1;
  return `c${Date.now().toString(36)}${counter}`;
}

/** "10%" → { percent: "10" }; "50" → { amount: "50" }; invalid → null. */
export function parseDiscount(text: string): { amount?: string; percent?: string } | null {
  const t = text.replace(/,/g, "").trim();
  if (!t) return {};
  if (t.endsWith("%")) {
    const p = t.slice(0, -1).trim();
    return /^\d+(\.\d{1,4})?$/.test(p) && Number(p) <= 100 ? { percent: p } : null;
  }
  return isMoney(t) ? { amount: t } : null;
}

function discountAmount(gross: string, text: string): string {
  const d = parseDiscount(text);
  if (!d) return ZERO;
  if (d.percent) return percentOf(gross, d.percent);
  return d.amount ? fromPaisa(toPaisa(d.amount)) : ZERO;
}

export function lineQty(line: CartLine): string {
  return line.product.kind === "SERIAL" ? String(line.serials.length) : line.qty;
}

export function validLine(line: CartLine): boolean {
  const q = lineQty(line);
  return isQty(q, line.product.decimals) && toPaisa(q) > BigInt(0) && isMoney(line.price) && parseDiscount(line.discount) !== null;
}

export type LineTotals = { gross: string; discount: string; share: string; net: string; tax: string; total: string; offer: Applied };

export type Totals = {
  lines: LineTotals[];
  /** Before any discount. */
  subtotal: string;
  lineDiscount: string;
  billDiscount: string;
  discount: string;
  /** Part of the discount that came from offers (line and bill). */
  offers: string;
  billOffer: Applied;
  /** The coupon on the bill took something off. */
  couponUsed: boolean;
  tax: string;
  total: string;
  items: number;
  /** A line has a discount over its amount, or the bill discount is over the bill. */
  invalid: string | null;
};

/**
 * @param promo offers running now (module promotions); the same rules as the server: an offer applies to a line in the
 *     base unit at the list price with nothing typed in its discount, and a bill offer only when no bill discount is typed
 */
export function totals(cart: Cart, taxMode: string, shopTaxRate: string | null | undefined, promo?: PromoContext | null): Totals {
  let invalid: string | null = null;
  const gross: string[] = [];
  const disc: string[] = [];
  for (const line of cart.lines) {
    const ok = validLine(line);
    const g = ok ? lineAmount(lineQty(line), line.price) : ZERO;
    let d = ok ? discountAmount(g, line.discount) : ZERO;
    if (cmpMoney(d, g) > 0) {
      invalid = `Discount on ${line.product.name} is more than the line amount`;
      d = g;
    }
    gross.push(g);
    disc.push(d);
  }
  const typedBill = parseDiscount(cart.billDiscount);
  const billTyped = !!typedBill && ((!!typedBill.amount && toPaisa(typedBill.amount) > BigInt(0)) || (!!typedBill.percent && Number(typedBill.percent) > 0));
  let lineOffers: Applied[] = cart.lines.map(() => NONE);
  let billOffer: Applied = NONE;
  let couponUsed = false;
  if (promo && promo.offers.length && cart.lines.length) {
    const offers = promo.offers
      .filter((o) => running(o, promo.date, promo.time))
      .map((o) => ({ ...o, couponCode: o.coupon ? (cart.coupon?.offerId === o.id ? cart.coupon.code : `#locked-${o.id}`) : null }));
    const result = applyOffers(
      offers,
      cart.lines.map((line, i) => ({
        productId: line.product.id,
        categoryId: line.product.categoryId ?? null,
        qty: validLine(line) ? lineQty(line) : "0",
        price: validLine(line) ? line.price : ZERO,
        eligible: validLine(line) && line.unitId === null && cmpMoney(line.price, line.listPrice) === 0 && cmpMoney(disc[i], ZERO) === 0,
      })),
      disc,
      billTyped,
      cart.coupon?.code ?? null,
    );
    lineOffers = result.lines;
    billOffer = result.bill;
    couponUsed = result.couponUsed;
    lineOffers.forEach((o, i) => {
      disc[i] = addMoney(disc[i], o.amount);
    });
  }
  const subtotal = gross.reduce((s, v) => addMoney(s, v), ZERO);
  const lineDiscount = disc.reduce((s, v) => addMoney(s, v), ZERO);
  const afterLine = subMoney(subtotal, lineDiscount);
  let billDiscount = discountAmount(afterLine, cart.billDiscount);
  if (typedBill === null) invalid = "The bill discount is not a valid amount";
  if (cmpMoney(billDiscount, afterLine) > 0) {
    invalid = "Discount is more than the bill";
    billDiscount = afterLine;
  }
  billDiscount = addMoney(billDiscount, billOffer.amount);
  const shares = spread(
    gross.map((g, i) => subMoney(g, disc[i])),
    billDiscount,
  );
  let taxTotal = ZERO;
  let total = ZERO;
  const lines = cart.lines.map((line, i) => {
    const net = subMoney(subMoney(gross[i], disc[i]), shares[i]);
    const rate = line.product.taxRate ?? shopTaxRate ?? "0";
    const t = tax(taxMode, net, rate);
    const lt = lineTotal(taxMode, net, t);
    taxTotal = addMoney(taxTotal, t);
    total = addMoney(total, lt);
    return { gross: gross[i], discount: disc[i], share: shares[i], net, tax: t, total: lt, offer: lineOffers[i] };
  });
  return {
    lines,
    subtotal,
    lineDiscount,
    billDiscount,
    discount: addMoney(lineDiscount, billDiscount),
    offers: addMoney(...lineOffers.map((o) => o.amount), billOffer.amount),
    billOffer,
    couponUsed,
    tax: taxTotal,
    total,
    items: cart.lines.length,
    invalid,
  };
}

export type Tender = { paid: string; change: string; due: string; error: string | null };

/** Payments against the total: change comes only from cash; anything short goes on the customer's account. */
export function tender(total: string, payments: Payment[]): Tender {
  const valid = payments.filter((p) => isMoney(p.amount) && p.method !== "ACCOUNT");
  const paid = valid.reduce((s, p) => addMoney(s, p.amount), ZERO);
  if (cmpMoney(paid, total) > 0) {
    const excess = subMoney(paid, total);
    const cash = valid.filter((p) => p.method === "CASH").reduce((s, p) => addMoney(s, p.amount), ZERO);
    if (cmpMoney(cash, excess) < 0) {
      return { paid, change: ZERO, due: ZERO, error: "Only cash can have change — card or wallet is more than the bill" };
    }
    return { paid, change: excess, due: ZERO, error: null };
  }
  return { paid, change: ZERO, due: subMoney(total, paid), error: null };
}

/** Add a product (or one more of it). A serial item keeps one line and collects its IMEIs. */
export function addProduct(cart: Cart, product: CartProduct, unit: PosUnit | null, qty = "1", serial?: string): Cart {
  const unitId = unit?.unitId ?? null;
  if (product.kind === "SERIAL") {
    const existing = cart.lines.find((l) => l.product.id === product.id);
    if (existing && serial) {
      if (existing.serials.includes(serial)) return cart;
      return replaceLine(cart, existing.key, { serials: [...existing.serials, serial] });
    }
  }
  else {
    // Batch items merge too while the batch is automatic (earliest expiry first); a chosen batch keeps its own line.
    const existing = cart.lines.find((l) => l.product.id === product.id && l.unitId === unitId && l.batchId === null);
    if (existing) {
      return replaceLine(cart, existing.key, { qty: addQty(existing.qty, qty) });
    }
  }
  const price = unit?.price ?? product.price;
  const line: CartLine = {
    key: lineKey(),
    product,
    unitId,
    unitName: unit?.name ?? product.unitName,
    qty: product.kind === "SERIAL" ? "0" : qty,
    price,
    listPrice: price,
    discount: "",
    batchId: null,
    serials: serial ? [serial] : [],
  };
  return { ...cart, lines: [...cart.lines, line] };
}

export function replaceLine(cart: Cart, key: string, patch: Partial<CartLine>): Cart {
  return { ...cart, lines: cart.lines.map((l) => (l.key === key ? { ...l, ...patch } : l)) };
}

export function removeLine(cart: Cart, key: string): Cart {
  return { ...cart, lines: cart.lines.filter((l) => l.key !== key) };
}

function addQty(a: string, b: string): string {
  if (!isQty(a) || !isQty(b)) return a;
  return trimQty(fromMilli(toMilli(a) + toMilli(b)));
}

/** "2.500" → "2.5", "3.000" → "3". */
export function trimQty(q: string): string {
  return q.includes(".") ? q.replace(/0+$/, "").replace(/\.$/, "") : q;
}

/** Body of POST /sales. */
export function saleBody(cart: Cart, payments: Payment[], tillSessionId: number | null) {
  const bill = parseDiscount(cart.billDiscount) ?? {};
  return {
    tillSessionId,
    customerId: cart.customer?.id ?? null,
    newCustomer: !cart.customer && cart.quick?.phone ? cart.quick : null,
    note: cart.note || null,
    couponCode: cart.coupon?.code ?? null,
    prescription: cart.prescription ?? null,
    orderType: cart.orderType ?? null,
    tableNo: cart.orderType === "DINE_IN" ? cart.tableNo || null : null,
    billDiscount: bill.amount ?? null,
    billDiscountPercent: bill.percent ?? null,
    lines: cart.lines.map((l) => {
      const d = parseDiscount(l.discount) ?? {};
      return {
        productId: l.product.id,
        unitId: l.unitId,
        qty: l.product.kind === "SERIAL" ? null : l.qty,
        price: l.price === l.listPrice ? null : l.price,
        discount: d.amount ?? null,
        discountPercent: d.percent ?? null,
        batchId: l.batchId,
        serials: l.product.kind === "SERIAL" ? l.serials : null,
        note: l.note?.trim() || null,
      };
    }),
    payments: payments
      .filter((p) => isMoney(p.amount) && toPaisa(p.amount) > BigInt(0))
      .map((p) => ({ method: p.method, amount: p.amount, reference: p.reference || null })),
  };
}
