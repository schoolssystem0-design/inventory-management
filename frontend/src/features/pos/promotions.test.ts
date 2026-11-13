import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { applyOffers, billOffer, lineOffer, type Offer, running } from "./promotions";
import { readScale } from "./scale";

type Json = Record<string, unknown>;
const vectors = JSON.parse(readFileSync(resolve(__dirname, "../../../../docs/test-vectors/promotions.json"), "utf8")) as Record<string, Json[]>;

const offer = (o: Json): Offer => ({ id: 1, ...(o as Partial<Offer>), kind: (o.kind as string) ?? "PERCENT" }) as Offer;

describe("promotions.json shared vectors", () => {
  it.each(vectors.lineAmount.map((v) => [v.name, v] as const))("line offer: %s", (_, v) => {
    expect(lineOffer(offer(v.offer as Json), { productId: 1, categoryId: null, qty: v.qty as string, price: v.price as string, eligible: true })).toBe(v.expected);
  });

  it.each(vectors.billAmount.map((v) => [v.name, v] as const))("bill offer: %s", (_, v) => {
    expect(billOffer(offer(v.offer as Json), v.bill as string)).toBe(v.expected);
  });

  it.each(vectors.running.map((v) => [v.name, v] as const))("running: %s", (_, v) => {
    expect(running(offer(v.offer as Json), v.date as string, v.time as string)).toBe(v.expected);
  });

  it.each(vectors.apply.map((v) => [v.name, v] as const))("apply: %s", (_, v) => {
    const lines = (v.lines as Json[]).map((l) => ({
      productId: l.productId as number,
      categoryId: (l.categoryId as number | null) ?? null,
      qty: l.qty as string,
      price: l.price as string,
      eligible: l.eligible as boolean,
    }));
    const typed = (v.lines as Json[]).map((l) => l.typedDiscount as string);
    const result = applyOffers((v.offers as Json[]).map((o) => offer(o)), lines, typed, v.billTyped as boolean, (v.coupon as string | null) ?? null);
    expect(result.lines.map((l) => ({ offerId: l.offerId, amount: l.amount }))).toEqual(v.expectedLines);
    expect({ offerId: result.bill.offerId, amount: result.bill.amount }).toEqual(v.expectedBill);
    expect(result.couponUsed).toBe(v.expectedCouponUsed);
  });

  it.each(vectors.scaleBarcode.map((v) => [v.name, v] as const))("scale label: %s", (_, v) => {
    const r = readScale(v.barcode as string, v.mode as string);
    if (v.expectedCode === null) expect(r).toBeNull();
    else expect(r).toEqual({ itemCode: v.expectedCode, value: v.expectedValue });
  });
});
