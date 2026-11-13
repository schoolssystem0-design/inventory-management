import { expect, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl, ROOT } from "./helpers";

test.describe("demo shop", () => {
  test("landing page on the bare domain", async ({ page }) => {
    await page.goto(`http://${ROOT}/`);
    await expect(page.getByRole("heading").first()).toBeVisible();
  });

  test("unknown shop code shows 'Shop not found'", async ({ page }) => {
    await page.goto(shopUrl("no-such-shop-xyz", "/login"));
    await expect(page.getByText("Shop not found")).toBeVisible();
  });

  test("wrong password is refused, right one signs in", async ({ page }) => {
    await page.goto(shopUrl("demo-mart", "/login"));
    await page.getByLabel("Username").fill("owner");
    await page.getByLabel("Password").fill("not-the-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Username or password is incorrect")).toBeVisible();
    await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
  });

  test("signed-out visitor is sent to sign in", async ({ page }) => {
    await page.goto(shopUrl("demo-mart", "/products"));
    await expect(page).toHaveURL(/\/login/);
  });

  test("every main page opens without errors", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
    for (const path of ["/", "/products", "/products/new", "/products/import", "/products/bulk-price", "/products/labels", "/catalogue", "/settings", "/branches", "/users", "/roles", "/security", "/account"]) {
      await page.goto(shopUrl("demo-mart", path));
      await expect(page.getByRole("heading", { level: 1 }).first(), path).toBeVisible();
    }
    expect(errors).toEqual([]);
  });

  test("Ctrl+K opens search and jumps to a page", async ({ page }) => {
    await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
    await page.keyboard.press("Control+k");
    await page.getByLabel("Search products or pages").fill("users");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(shopUrl("demo-mart", "/users"));
  });

  test("sign out returns to the sign-in page", async ({ page }) => {
    await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto(shopUrl("demo-mart", "/products"));
    await expect(page).toHaveURL(/\/login/);
  });

  test("shop session does not open another shop", async ({ page }) => {
    await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
    await page.goto(shopUrl("demo-pharmacy", "/products"));
    await expect(page).toHaveURL(/\/login/);
  });
});
