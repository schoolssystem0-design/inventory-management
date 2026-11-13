import { describe, expect, it } from "vitest";
import { addProduct, type Cart, type CartProduct, EMPTY_CART, parseDiscount, replaceLine, saleBody, tender, totals } from "./cart";
import { CatalogIndex } from "./catalog-cache";

const rice: CartProduct = { id: 1, name: "Basmati rice", sku: "R1", kind: "STANDARD", price: "180.00", decimals: true, unitName: "kg", units: [] };
const soap: CartProduct = {
  id: 2,
  name: "Soap",
  sku: "S1",
  kind: "STANDARD",
  price: "100.00",
  decimals: false,
  unitName: "pcs",
  units: [{ unitId: 9, name: "Carton", factor: "12", price: "1150.00", barcode: "999" }],
};
const phone: CartProduct = { id: 3, name: "Phone", sku: "P1", kind: "SERIAL", price: "50000.00", decimals: false, unitName: "pcs", units: [] };

function cart(...steps: ((c: Cart) => Cart)[]) {
  return steps.reduce((c, f) => f(c), EMPTY_CART);
}

describe("cart totals (same rules as the server's SaleMath)", () => {
  it("weighed quantity × price rounds half up to paisa", () => {
    const c = cart((c) => addProduct(c, rice, null, "1.255"));
    expect(totals(c, "NONE", "0").total).toBe("225.90");
  });

  it("adding the same item again adds to its quantity; a pack unit is its own line", () => {
    const c = cart(
      (c) => addProduct(c, soap, null),
      (c) => addProduct(c, soap, null),
      (c) => addProduct(c, soap, soap.units[0]),
    );
    expect(c.lines).toHaveLength(2);
    expect(c.lines[0].qty).toBe("2");
    expect(totals(c, "NONE", "0").total).toBe("1350.00");
  });

  it("bill discount is spread in proportion and the last line takes the remainder", () => {
    let c = cart(
      (c) => addProduct(c, soap, null),
      (c) => addProduct(c, rice, null, "1"),
    );
    c = { ...c, billDiscount: "10" };
    const t = totals(c, "NONE", "0");
    expect(t.lines.map((l) => l.share)).toEqual(["3.57", "6.43"]);
    expect(t.total).toBe("270.00");
  });

  it("line percent discount, then exclusive tax on the net", () => {
    let c = cart((c) => addProduct(c, soap, null, "3"));
    c = replaceLine(c, c.lines[0].key, { discount: "10%" });
    const t = totals(c, "EXCLUSIVE", "17");
    expect(t.lineDiscount).toBe("30.00");
    expect(t.tax).toBe("45.90");
    expect(t.total).toBe("315.90");
  });

  it("inclusive tax is inside the price", () => {
    const c = cart((c) => addProduct(c, soap, null));
    const t = totals(c, "INCLUSIVE", "17");
    expect(t.tax).toBe("14.53");
    expect(t.total).toBe("100.00");
  });

  it("a discount over the line amount is flagged", () => {
    let c = cart((c) => addProduct(c, soap, null));
    c = replaceLine(c, c.lines[0].key, { discount: "150" });
    expect(totals(c, "NONE", "0").invalid).toMatch(/more than the line/);
  });

  it("batch items merge while the batch is automatic", () => {
    const med: CartProduct = { ...soap, id: 4, kind: "BATCH", units: [] };
    let c = cart((c) => addProduct(c, med, null), (c) => addProduct(c, med, null));
    expect(c.lines).toHaveLength(1);
    expect(c.lines[0].qty).toBe("2");
    c = replaceLine(c, c.lines[0].key, { batchId: 7 });
    c = addProduct(c, med, null);
    expect(c.lines).toHaveLength(2);
  });

  it("serial items count their IMEIs and never repeat one", () => {
    const c = cart(
      (c) => addProduct(c, phone, null, "1", "IMEI1"),
      (c) => addProduct(c, phone, null, "1", "IMEI2"),
      (c) => addProduct(c, phone, null, "1", "IMEI2"),
    );
    expect(c.lines).toHaveLength(1);
    expect(c.lines[0].serials).toEqual(["IMEI1", "IMEI2"]);
    expect(totals(c, "NONE", "0").total).toBe("100000.00");
  });
});

describe("tender", () => {
  it("change comes from cash", () => {
    expect(tender("270.00", [{ method: "CASH", amount: "500" }])).toEqual({ paid: "500.00", change: "230.00", due: "0.00", error: null });
  });
  it("card over the bill is refused", () => {
    expect(tender("270.00", [{ method: "CARD", amount: "300" }]).error).toMatch(/Only cash/);
  });
  it("split payment short leaves a due", () => {
    const t = tender("1000.00", [
      { method: "CASH", amount: "400" },
      { method: "JAZZCASH", amount: "350" },
    ]);
    expect(t.due).toBe("250.00");
  });
});

describe("discount text", () => {
  it("reads amounts and percents", () => {
    expect(parseDiscount("50")).toEqual({ amount: "50" });
    expect(parseDiscount("12.5%")).toEqual({ percent: "12.5" });
    expect(parseDiscount("")).toEqual({});
    expect(parseDiscount("abc")).toBeNull();
    expect(parseDiscount("120%")).toBeNull();
  });
});

describe("sale body", () => {
  it("sends only changed prices and the right discount field", () => {
    let c = cart((c) => addProduct(c, soap, null, "2"));
    c = { ...replaceLine(c, c.lines[0].key, { discount: "5%" }), billDiscount: "20", quick: { name: "Ali", phone: "03001234567" } };
    const body = saleBody(c, [{ method: "CASH", amount: "200" }, { method: "CARD", amount: "0" }], 7);
    expect(body.lines[0]).toMatchObject({ productId: 2, qty: "2", price: null, discountPercent: "5", discount: null });
    expect(body.billDiscount).toBe("20");
    expect(body.payments).toHaveLength(1);
    expect(body.newCustomer).toEqual({ name: "Ali", phone: "03001234567" });
  });
});

describe("POS search speed", () => {
  it("searches 50,000 products in under 100 ms", () => {
    const items = Array.from({ length: 50_000 }, (_, i) => ({
      id: i,
      sku: `SKU${i}`,
      name: `Product ${i} ${["shampoo", "rice", "soap", "biscuit", "panadol"][i % 5]} ${i % 97}`,
      urduName: null,
      kind: "STANDARD",
      price: "100.00",
      minPrice: null,
      taxRate: null,
      decimals: false,
      unitName: "pcs",
      units: [],
      barcodes: [`89${String(i).padStart(11, "0")}`],
      partNumbers: [],
      formula: null,
      stock: "5",
    }));
    const index = new CatalogIndex(items as never);
    const start = performance.now();
    const hits = index.search("soap 42");
    const code = index.code("8900000049999");
    const ms = performance.now() - start;
    expect(hits.length).toBeGreaterThan(0);
    expect(code?.item.id).toBe(49_999);
    expect(ms).toBeLessThan(100);
  });
});

describe("offers on the bill (same case as the server's Phase2ModulesIT)", () => {
  const offers = [
    { id: 1, kind: "BUY_GET", buyQty: "2.000", getQty: "1.000", scope: "PRODUCTS", productIds: [2], coupon: false },
    { id: 2, kind: "PERCENT", value: "10.00", scope: "ALL", coupon: true },
  ];
  const promo = { offers, date: "2026-11-13", time: "12:00" };

  it("3 soap at Rs 100 with buy 2 get 1 free: Rs 200, offer shown on the line", () => {
    const c = cart((c) => addProduct(c, soap, null, "3"));
    const t = totals(c, "NONE", "0", promo);
    expect(t.total).toBe("200.00");
    expect(t.offers).toBe("100.00");
    expect(t.lines[0].offer.offerId).toBe(1);
  });

  it("a typed line discount wins over the offer", () => {
    let c = cart((c) => addProduct(c, soap, null, "3"));
    c = replaceLine(c, c.lines[0].key, { discount: "5" });
    const t = totals(c, "NONE", "0", promo);
    expect(t.total).toBe("295.00");
    expect(t.offers).toBe("0.00");
  });

  it("a coupon offer applies only with its coupon on the bill", () => {
    const base = cart((c) => addProduct(c, rice, null, "1"));
    expect(totals(base, "NONE", "0", promo).total).toBe("180.00");
    const withCoupon = { ...base, coupon: { code: "SAVE10", offerId: 2, name: "Ten off" } };
    const t = totals(withCoupon, "NONE", "0", promo);
    expect(t.total).toBe("162.00");
    expect(t.couponUsed).toBe(true);
  });

  it("the sale body carries the coupon and the prescription", () => {
    const c = { ...cart((c) => addProduct(c, soap, null)), coupon: { code: "SAVE10", offerId: 2, name: "x" }, prescription: { patientName: "A", doctorName: "Dr B" } };
    const body = saleBody(c, [{ method: "CASH", amount: "100" }], 1);
    expect(body.couponCode).toBe("SAVE10");
    expect(body.prescription).toEqual({ patientName: "A", doctorName: "Dr B" });
  });
});

describe("fast food orders", () => {
  const burger: CartProduct = { id: 9, name: "Zinger burger", sku: "Z1", kind: "RECIPE", price: "550.00", decimals: false, unitName: "piece", units: [] };

  it("the sale body carries the order type, the table only for dine-in, and kitchen notes", () => {
    let c = cart((c) => addProduct(c, burger, null, "2"));
    c = replaceLine(c, c.lines[0].key, { note: " No onions " });
    const dineIn = saleBody({ ...c, orderType: "DINE_IN", tableNo: "5" }, [{ method: "CASH", amount: "1100" }], 1);
    expect(dineIn.orderType).toBe("DINE_IN");
    expect(dineIn.tableNo).toBe("5");
    expect(dineIn.lines[0].note).toBe("No onions");
    const takeaway = saleBody({ ...c, orderType: "TAKEAWAY", tableNo: "5" }, [{ method: "CASH", amount: "1100" }], 1);
    expect(takeaway.tableNo).toBeNull();
    expect(totals(c, "NONE", "0").total).toBe("1100.00");
  });
});
