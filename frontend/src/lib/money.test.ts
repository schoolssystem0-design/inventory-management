import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  averageCost,
  dueBefore,
  lineAmount,
  lineTotal,
  maxRefund,
  percentOf,
  reorderSuggestion,
  returnValue,
  spread,
  tax,
  toPaisa,
  fromPaisa,
} from "@/lib/money";

type Vectors = {
  averageCost: { name: string; onHand: string; currentAvg: string; qtyIn: string; unitCost: string; expectedAvg: string }[];
  billDiscountSpread: { name: string; lines: { net: string }[]; billDiscount: string; expectedLineDiscounts: string[] }[];
  returnRefund: {
    name: string;
    saleTotal: string;
    alreadyReturned: string;
    paid: string;
    returnValue: string;
    expectedDueBefore: string;
    expectedMaxRefund: string;
  }[];
  reorderSuggestion: { name: string; reorderLevel: string; sold30: string; onHand: string; onOrder: string; expectedSuggest: string }[];
  weighedItem: { name: string; qty: string; price: string; expectedLine: string }[];
};

// The same file the backend's SaleMathTest reads, so till and server agree to the paisa.
const vectors = JSON.parse(readFileSync(resolve(__dirname, "../../../docs/test-vectors/money.json"), "utf8")) as Vectors;

describe("money.json shared vectors", () => {
  it.each(vectors.averageCost)("average cost: $name", (v) => {
    expect(averageCost(v.onHand, v.currentAvg, v.qtyIn, v.unitCost)).toBe(v.expectedAvg);
  });
  it.each(vectors.billDiscountSpread)("bill discount: $name", (v) => {
    expect(spread(v.lines.map((l) => l.net), v.billDiscount)).toEqual(v.expectedLineDiscounts);
  });
  it.each(vectors.returnRefund)("return refund: $name", (v) => {
    expect(dueBefore(v.saleTotal, v.alreadyReturned, v.paid)).toBe(v.expectedDueBefore);
    expect(maxRefund(v.saleTotal, v.alreadyReturned, v.paid, v.returnValue)).toBe(v.expectedMaxRefund);
  });
  it.each(vectors.reorderSuggestion)("reorder: $name", (v) => {
    expect(reorderSuggestion(v.reorderLevel, v.sold30, v.onHand, v.onOrder)).toBe(v.expectedSuggest);
  });
  it.each(vectors.weighedItem)("weighed: $name", (v) => {
    expect(lineAmount(v.qty, v.price)).toBe(v.expectedLine);
  });
});

describe("cart maths", () => {
  it("never uses floating point", () => {
    expect(fromPaisa(toPaisa("0.1") + toPaisa("0.2"))).toBe("0.30");
    expect(lineAmount("3", "33.33")).toBe("99.99");
  });
  it("tax inclusive and exclusive", () => {
    expect(tax("INCLUSIVE", "1170.00", "17")).toBe("170.00");
    expect(tax("EXCLUSIVE", "1000.00", "17")).toBe("170.00");
    expect(lineTotal("EXCLUSIVE", "1000.00", "170.00")).toBe("1170.00");
    expect(tax("NONE", "1000.00", "17")).toBe("0.00");
  });
  it("percent discount", () => {
    expect(percentOf("2150.00", "5")).toBe("107.50");
  });
  it("returns in parts add up to the line", () => {
    const a = returnValue("100.00", "3", "0", "1");
    const b = returnValue("100.00", "3", "1", "1");
    const c = returnValue("100.00", "3", "2", "1");
    expect([a, b, c]).toEqual(["33.33", "33.34", "33.33"]);
  });
});
