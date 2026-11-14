import { describe, expect, it } from "vitest";
import { areaOf, decideRoute, rewriteLegacyShopLink } from "./routing";

const ROOT = "selectcodepos.com";

describe("areaOf", () => {
  it("knows the platform, shops and the landing page", () => {
    expect(areaOf("admin.selectcodepos.com", ROOT)).toEqual({ kind: "platform" });
    expect(areaOf("alimobiles.selectcodepos.com", ROOT)).toEqual({ kind: "shop", code: "alimobiles" });
    expect(areaOf("demo-mart.localhost:3000", "localhost")).toEqual({ kind: "shop", code: "demo-mart" });
    expect(areaOf("selectcodepos.com", ROOT)).toEqual({ kind: "landing" });
    expect(areaOf("a.b.selectcodepos.com", ROOT)).toEqual({ kind: "landing" });
    expect(areaOf("evil.com", ROOT)).toEqual({ kind: "landing" });
  });
});

describe("decideRoute", () => {
  it("rewrites by host", () => {
    expect(decideRoute("admin.selectcodepos.com", "/shops/4", ROOT)).toEqual({ kind: "rewrite", pathname: "/platform/shops/4" });
    expect(decideRoute("admin.selectcodepos.com", "/", ROOT)).toEqual({ kind: "rewrite", pathname: "/platform" });
    expect(decideRoute("admin.selectcodepos.com", "/support/products", ROOT)).toEqual({
      kind: "rewrite",
      pathname: "/shop/products",
    });
    expect(decideRoute("ali.selectcodepos.com", "/products/7", ROOT)).toEqual({ kind: "rewrite", pathname: "/shop/products/7" });
    expect(decideRoute("selectcodepos.com", "/", ROOT)).toEqual({ kind: "rewrite", pathname: "/landing" });
  });

  it("never serves internal folders directly", () => {
    expect(decideRoute("ali.selectcodepos.com", "/platform/shops", ROOT)).toEqual({ kind: "notFound" });
    expect(decideRoute("admin.selectcodepos.com", "/shop/products", ROOT)).toEqual({ kind: "notFound" });
  });

  it("serves platform and shops on the apex host for vercel.app (no admin subdomain)", () => {
    const vercel = "inventory-pos-frontend.vercel.app";
    expect(decideRoute(vercel, "/login", vercel)).toEqual({ kind: "rewrite", pathname: "/platform/login" });
    expect(decideRoute(vercel, "/platform/shops", vercel)).toEqual({ kind: "rewrite", pathname: "/platform/shops" });
    expect(decideRoute(vercel, "/shops", vercel)).toEqual({ kind: "rewrite", pathname: "/platform/shops" });
    expect(decideRoute(vercel, "/s/demo-mart/login", vercel)).toEqual({ kind: "rewrite", pathname: "/shop/login" });
  });
});

describe("rewriteLegacyShopLink", () => {
  it("rewrites shop subdomain links on vercel.app", () => {
    const root = "inventory-pos-frontend.vercel.app";
    expect(rewriteLegacyShopLink("https://demo-mart.inventory-pos-frontend.vercel.app/setup?token=abc", root)).toBe(
      "https://inventory-pos-frontend.vercel.app/s/demo-mart/setup?token=abc",
    );
  });
});
