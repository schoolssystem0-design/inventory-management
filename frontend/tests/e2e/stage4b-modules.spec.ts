import { expect, type Page, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl } from "./helpers";

/** Stage 4b screens per demo shop: each opens with no errors and shows its demo records. */
const BY_SHOP: Record<string, string[]> = {
  "demo-mart": ["/promotions", "/settings", "/customers"],
  "demo-mobile": ["/repairs", "/repairs?status="],
  "demo-pharmacy": ["/prescriptions", "/prescriptions/register", "/settings"],
  "demo-electronics": ["/installments", "/installments/due", "/deliveries", "/challans"],
  "demo-hardware": ["/challans", "/challans/new", "/deliveries", "/deliveries?status="],
};

function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
  page.on("response", (r) => {
    if (r.url().includes("/api/v1/") && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`);
  });
  return errors;
}

for (const [code, paths] of Object.entries(BY_SHOP)) {
  test(`${code}: Stage 4b pages open without errors`, async ({ page }) => {
    test.setTimeout(180_000);
    const errors = watchErrors(page);
    await shopSignIn(page, code, "owner", DEMO_PASSWORD);
    for (const path of paths) {
      await page.goto(shopUrl(code, path));
      await expect(page.getByRole("heading", { level: 1 }).first(), path).toBeVisible({ timeout: 30_000 });
      await page.waitForLoadState("networkidle");
    }
    expect(errors).toEqual([]);
  });
}

test("demo-mobile: a repair job opens, its detail and job card work", async ({ page }) => {
  test.setTimeout(120_000);
  const errors = watchErrors(page);
  await shopSignIn(page, "demo-mobile", "owner", DEMO_PASSWORD);
  await page.goto(shopUrl("demo-mobile", "/repairs?status="));
  await page.locator("tbody tr").first().click();
  await expect(page).toHaveURL(/\/repairs\/\d+/);
  await expect(page.getByRole("button", { name: "Job card" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "History" })).toBeVisible();
  expect(errors).toEqual([]);
});

test("demo-mart: an offer shows on the bill at the till", async ({ page }) => {
  test.setTimeout(120_000);
  const errors = watchErrors(page);
  await shopSignIn(page, "demo-mart", "owner", DEMO_PASSWORD);
  // The demo "Buy 2 get 1 free" offer is on the first grocery item.
  const offer = await page.evaluate(async () => {
    const list = (await (await fetch("/api/v1/promotions")).json()) as { kind: string; productIds: number[] }[];
    const buyGet = list.find((o) => o.kind === "BUY_GET");
    if (!buyGet) return null;
    const p = (await (await fetch(`/api/v1/products/${buyGet.productIds[0]}`)).json()) as { sku: string; name: string };
    return p;
  });
  expect(offer, "demo buy-get offer").toBeTruthy();
  await page.goto(shopUrl("demo-mart", "/pos"));
  const open = page.getByRole("heading", { name: "Open your till" });
  const search = page.getByLabel("Scan or search products");
  await expect(open.or(search)).toBeVisible({ timeout: 30_000 });
  if (await open.isVisible()) {
    await page.getByLabel("Cash in the drawer (opening float)").fill("5000");
    await page.getByRole("button", { name: "Open till" }).click();
  }
  await expect(page.getByText(/products ready/)).toBeVisible({ timeout: 30_000 });
  await search.fill(`3*${offer!.sku}`);
  await search.press("Enter");
  const bill = page.getByRole("list", { name: "Items on the bill" });
  await expect(bill.getByText("Buy 2 get 1 free")).toBeVisible();
  await expect(page.getByText(/^Offers/)).toBeVisible();
  expect(errors).toEqual([]);
});
