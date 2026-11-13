import { expect, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl } from "./helpers";

const COMMON = [
  "/",
  "/pos",
  "/sales",
  "/customers",
  "/till",
  "/stock",
  "/stock/adjustments",
  "/stock/adjustments/new",
  "/stock/opening",
  "/stock/counts",
  "/stock/transfers",
  "/stock/alerts",
  "/stock/movements",
  "/suppliers",
  "/purchases",
  "/purchases/new",
  "/purchases/receive",
  "/purchases/returns",
  "/purchases/returns/new",
  "/purchases/reorder",
  "/expenses",
  "/cash",
  "/profit",
  "/reports",
  "/exports",
  "/subscription",
];

const BY_SHOP: Record<string, string[]> = {
  "demo-mart": [...COMMON, "/quotations", "/quotations/new", "/stock/transfers/new"],
  "demo-mobile": [...COMMON, "/imei", "/buybacks", "/warranty"],
  "demo-pharmacy": [...COMMON],
};

for (const [code, paths] of Object.entries(BY_SHOP)) {
  test(`${code}: every Stage 4 page opens without errors`, async ({ page }) => {
    test.setTimeout(240_000);
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
    page.on("response", (r) => {
      if (r.url().includes("/api/v1/") && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`);
    });
    await shopSignIn(page, code, "owner", DEMO_PASSWORD);
    for (const path of paths) {
      await page.goto(shopUrl(code, path));
      await expect(page.getByRole("heading", { level: 1 }).first(), path).toBeVisible({ timeout: 30_000 });
      await page.waitForLoadState("networkidle");
    }
    expect(errors).toEqual([]);
  });
}
