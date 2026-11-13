import { describe, expect, it } from "vitest";
import { label, money, parseAmount, plural, qty } from "./format";

describe("money", () => {
  it("groups thousands and always shows two decimals", () => {
    expect(money("1250.5")).toBe("Rs 1,250.50");
    expect(money("1234567.00")).toBe("Rs 1,234,567.00");
    expect(money("80")).toBe("Rs 80.00");
    expect(money("-15.5")).toBe("-Rs 15.50");
    expect(money("0.00", false)).toBe("0.00");
    expect(money(null)).toBe("—");
  });
});

describe("qty", () => {
  it("drops trailing zeros", () => {
    expect(qty("12.500")).toBe("12.5");
    expect(qty("3.000")).toBe("3");
    expect(qty("1250.250")).toBe("1,250.25");
  });
});

describe("parseAmount", () => {
  it("accepts what people type and refuses the rest", () => {
    expect(parseAmount("1,250")).toBe("1250");
    expect(parseAmount(" 99.5 ")).toBe("99.5");
    expect(parseAmount("12.345")).toBeNull();
    expect(parseAmount("12.345", 3)).toBe("12.345");
    expect(parseAmount("Rs 10")).toBeNull();
    expect(parseAmount("-5")).toBeNull();
    expect(parseAmount("")).toBeNull();
  });
});

describe("labels", () => {
  it("names enum values", () => {
    expect(label("READ_ONLY")).toBe("Read-only");
    expect(label("SOMETHING_NEW")).toBe("Something new");
    expect(plural(1, "product")).toBe("1 product");
    expect(plural(1200, "product")).toBe("1,200 products");
  });
});
