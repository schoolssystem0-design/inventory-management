import { expect, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl } from "./helpers";

// Runs in the "phone" project (Pixel 7 viewport).
test("phone: menu opens, pages fit the screen", async ({ page }) => {
  await shopSignIn(page, "demo-mobile", "owner", DEMO_PASSWORD);
  for (const path of ["/", "/products", "/products/new", "/settings", "/users"]) {
    await page.goto(shopUrl("demo-mobile", path));
    await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `horizontal scroll on ${path}`).toBeLessThanOrEqual(1);
  }
  await page.getByRole("button", { name: /menu/i }).first().click();
  await page.getByRole("link", { name: "Products" }).first().click();
  await expect(page).toHaveURL(shopUrl("demo-mobile", "/products"));
});
