import { expect, type Page, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl } from "./helpers";

type PosItem = { id: number; name: string; sku: string; kind: string; price: string; stock: string | null; barcodes: string[] };

async function get<T>(page: Page, code: string, path: string): Promise<T> {
  // Through the page: Node cannot resolve *.localhost, the browser can, and it carries the session cookie.
  if (!page.url().startsWith(shopUrl(code, ""))) await page.goto(shopUrl(code, "/"));
  const r = await page.evaluate(async (p) => {
    const res = await fetch(`/api/v1${p}`, { credentials: "include" });
    return { status: res.status, body: res.ok ? await res.json() : null };
  }, path);
  expect(r.status, path).toBe(200);
  return r.body as T;
}

/** A sellable item with stock; its SKU scans like a barcode (the demo items have no barcodes). */
async function pickItem(page: Page, code: string, kinds = ["STANDARD", "BATCH"]) {
  const catalog = await get<{ items: PosItem[] }>(page, code, "/pos/catalog");
  const item = catalog.items.find((i) => kinds.includes(i.kind) && i.stock && Number(i.stock) >= 3 && Number(i.price) > 0);
  expect(item, "a stocked item").toBeTruthy();
  return item!;
}

async function openTill(page: Page) {
  const open = page.getByRole("heading", { name: "Open your till" });
  const search = page.getByLabel("Scan or search products");
  await expect(open.or(search)).toBeVisible({ timeout: 30_000 });
  if (await open.isVisible()) {
    await page.getByLabel("Cash in the drawer (opening float)").fill("5000");
    await page.getByRole("button", { name: "Open till" }).click();
  }
  await expect(search).toBeVisible();
}

for (const code of ["demo-mart", "demo-mobile", "demo-pharmacy"]) {
  test.describe(code, () => {
    test.beforeEach(async ({ page }) => {
      await shopSignIn(page, code, "owner", DEMO_PASSWORD);
    });

    test("sale at the till, then a return of one item", async ({ page }) => {
      test.setTimeout(180_000);
      const item = await pickItem(page, code);
      await page.goto(shopUrl(code, "/pos"));
      await openTill(page);
      const search = page.getByLabel("Scan or search products");
      // Scan the barcode twice: one line, quantity 2.
      await search.fill(item.sku);
      await search.press("Enter");
      await search.fill(item.sku);
      await search.press("Enter");
      const bill = page.getByRole("list", { name: "Items on the bill" });
      await expect(bill.getByText(item.name)).toBeVisible();
      await expect(bill.getByRole("listitem")).toHaveCount(1);
      await page.keyboard.press("F9");
      // Rx medicines ask for the prescription first (MED-08).
      const rx = page.getByRole("dialog", { name: "Prescription" });
      await expect(rx.or(page.getByRole("dialog", { name: /Collect/ }))).toBeVisible();
      if (await rx.isVisible()) {
        await rx.getByRole("textbox", { name: /^Patient(\s*\*)?$/ }).fill("E2E patient");
        await rx.getByRole("textbox", { name: /^Doctor(\s*\*)?$/ }).fill("Dr. Test");
        await rx.getByRole("button", { name: /Save and pay/ }).click();
      }
      await expect(page.getByRole("dialog", { name: /Collect/ })).toBeVisible();
      await page.getByRole("button", { name: "Complete sale" }).click();
      const done = page.getByRole("dialog", { name: /^Sale / });
      await expect(done).toBeVisible({ timeout: 30_000 });
      const number = (await done.getByRole("heading").first().textContent())!.replace("Sale ", "").trim();
      await page.getByRole("button", { name: /New sale/ }).click();
      await expect(bill.getByRole("listitem")).toHaveText(/Scan or search/);

      // Find the sale and return one.
      await page.goto(shopUrl(code, "/sales"));
      await page.getByLabel("Invoice no., customer, phone or IMEI").fill(number);
      await page.getByText(number, { exact: true }).click();
      await expect(page.getByRole("heading", { level: 1 })).toContainText(number);
      await page.getByRole("button", { name: "Return" }).click();
      await page.getByLabel(`Return quantity of ${item.name}`).fill("1");
      await page.getByLabel("Reason").fill("Customer changed mind");
      await page.getByRole("button", { name: /^Return Rs/ }).click();
      await expect(page.getByText("Return done")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByRole("heading", { level: 1 })).toContainText("Part returned");
    });

    test("receive goods from a supplier", async ({ page }) => {
      test.setTimeout(180_000);
      const suppliers = await get<{ items: { id: number; name: string }[] }>(page, code, "/suppliers?active=true&size=1");
      expect(suppliers.items.length).toBeGreaterThan(0);
      const item = await pickItem(page, code);
      await page.goto(shopUrl(code, `/purchases/receive?supplier=${suppliers.items[0].id}`));
      await expect(page.getByRole("heading", { name: "Receive goods" })).toBeVisible();
      await page.getByLabel("Find a product").fill(item.sku);
      await page.getByLabel("Find a product").press("Enter");
      await expect(page.getByLabel(`Quantity of ${item.name}`)).toBeVisible();
      await page.getByLabel(`Quantity of ${item.name}`).fill("6");
      await page.getByLabel(`Cost of ${item.name}`).fill("50");
      if (item.kind === "BATCH") {
        await page.getByLabel(`Batch of ${item.name}`).fill(`E2E${Date.now().toString(36)}`);
        await page.getByLabel(`Expiry of ${item.name}`).fill("2028-12-31");
      }
      await page.getByRole("button", { name: "Save and add to stock" }).click();
      await expect(page).toHaveURL(/\/purchases\/grns\/\d+/, { timeout: 30_000 });
      await expect(page.getByText(item.name)).toBeVisible();
    });

    test("stock count by scanning, then post", async ({ page }) => {
      test.setTimeout(180_000);
      const item = await pickItem(page, code);
      await page.goto(shopUrl(code, "/stock/counts"));
      // One open count per branch: carry on with one left open, or start a new one.
      const open = page.locator("tbody tr").filter({ hasText: "Open" });
      await expect(page.locator("tbody tr").first().or(page.getByText("No counts yet"))).toBeVisible();
      if (await open.count()) await open.first().click();
      else {
        await page.getByRole("button", { name: "Start a count" }).click();
        await page.getByRole("button", { name: "Start", exact: true }).click();
      }
      await expect(page).toHaveURL(/\/stock\/counts\/\d+/, { timeout: 60_000 });
      const scan = page.getByLabel("Scan to count");
      await scan.fill(item.sku);
      await scan.press("Enter");
      await scan.fill(item.sku);
      await scan.press("Enter");
      await expect(page.getByLabel(`Counted ${item.name}`, { exact: true }).first()).not.toHaveValue("");
      await expect(page.getByText("Saved", { exact: true })).toBeVisible({ timeout: 15_000 });
      await page.getByRole("button", { name: "Post count" }).click();
      const confirm = page.getByRole("dialog", { name: "Post this count?" });
      await confirm.getByRole("button", { name: "Post count" }).click();
      await expect(confirm).toBeHidden({ timeout: 30_000 });
      await expect(page.getByRole("heading", { level: 1 })).toContainText("Posted", { timeout: 30_000 });
    });

    test("close the till with the Z-report", async ({ page }) => {
      test.setTimeout(120_000);
      await page.goto(shopUrl(code, "/pos"));
      await openTill(page);
      await page.goto(shopUrl(code, "/till"));
      await page.getByRole("button", { name: "Close till" }).first().click();
      await page.getByLabel("Or type the total counted").fill("5000");
      await page.getByRole("button", { name: /^Close with/ }).click();
      await expect(page.getByRole("dialog", { name: "Z-report" })).toBeVisible({ timeout: 30_000 });
      await expect(page.frameLocator('iframe[title="Z-report"]').getByText("Z REPORT", { exact: false })).toBeVisible();
    });
  });
}

test("demo-mobile: sell a phone by scanning its IMEI, then find it in IMEI search", async ({ page }) => {
  test.setTimeout(180_000);
  await shopSignIn(page, "demo-mobile", "owner", DEMO_PASSWORD);
  const serials = await get<{ items: { serial: string; productName: string }[] }>(page, "demo-mobile", "/serials?status=IN_STOCK&size=1");
  expect(serials.items.length, "a phone in stock").toBeGreaterThan(0);
  const { serial, productName } = serials.items[0];
  await page.goto(shopUrl("demo-mobile", "/pos"));
  await openTill(page);
  const search = page.getByLabel("Scan or search products");
  await expect(page.getByText(/products ready/)).toBeVisible({ timeout: 30_000 });
  await search.fill(serial);
  await search.press("Enter");
  const bill = page.getByRole("list", { name: "Items on the bill" });
  await expect(bill.getByText(productName)).toBeVisible();
  await expect(bill.getByText(serial)).toBeVisible();
  await page.keyboard.press("F9");
  await page.getByRole("button", { name: "Complete sale" }).click();
  await expect(page.getByRole("dialog", { name: /^Sale / })).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: /New sale/ }).click();

  await page.goto(shopUrl("demo-mobile", "/imei"));
  await page.getByLabel("IMEI, serial or model").fill(serial);
  await page.getByRole("button", { name: serial }).click();
  const story = page.getByRole("dialog", { name: `IMEI ${serial}` });
  await expect(story.getByText("Sold", { exact: true }).first()).toBeVisible();
});
