import { expect, type Page, test } from "@playwright/test";
import { DEMO_PASSWORD, shopSignIn, shopUrl } from "./helpers";

/**
 * Fast food shop end to end (Stage 4c) on demo-fastfood: a dine-in order with a kitchen note at the till, its token,
 * the kitchen screen moving it to collected, and the menu item's recipe and the stock it used.
 */
const CODE = "demo-fastfood";

function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
  page.on("response", (r) => {
    if (r.url().includes("/api/v1/") && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`);
  });
  return errors;
}

async function openTill(page: Page) {
  const open = page.getByRole("heading", { name: "Open your till" });
  const search = page.getByLabel("Scan or search products");
  await expect(open.or(search)).toBeVisible({ timeout: 30_000 });
  if (await open.isVisible()) {
    await page.getByLabel("Cash in the drawer (opening float)").fill("5000");
    await page.getByRole("button", { name: "Open till" }).click();
  }
  await expect(page.getByText(/products ready/)).toBeVisible({ timeout: 30_000 });
}

test("fast food: dine-in order with a note goes to the kitchen and is collected", async ({ page }) => {
  test.setTimeout(180_000);
  const errors = watchErrors(page);
  await shopSignIn(page, CODE, "owner", DEMO_PASSWORD);
  await page.evaluate(() => localStorage.setItem("pos-autoprint", "0"));
  await page.goto(shopUrl(CODE, "/pos"));
  await openTill(page);

  // Dine-in at table 9: a Zinger burger with a note, and the Zinger deal.
  await page.getByRole("button", { name: "Dine-in" }).click();
  await page.getByLabel("Table number").fill("9");
  const search = page.getByLabel("Scan or search products");
  await search.fill("zinger burger");
  await page.getByRole("option").filter({ hasText: "Zinger burger" }).first().click();
  const bill = page.getByRole("list", { name: "Items on the bill" });
  await bill.getByRole("button", { name: /^Zinger burger/ }).click();
  await page.getByLabel("Note for the kitchen").fill("No onions");
  await page.getByRole("button", { name: "Save" }).click();
  await search.fill("zinger deal");
  await page.getByRole("option").filter({ hasText: "Zinger deal" }).first().click();
  await expect(bill.getByRole("listitem")).toHaveCount(2);

  await page.keyboard.press("F9");
  await page.getByRole("button", { name: "Complete sale" }).click();
  const done = page.getByRole("dialog", { name: /^Sale / });
  await expect(done).toBeVisible({ timeout: 30_000 });
  const tokenText = await done.getByLabel(/^Token \d+$/).textContent();
  const token = tokenText!.replace("#", "").trim();
  await expect(done.getByText("Dine-in · Table 9")).toBeVisible();
  await page.getByRole("button", { name: /New sale/ }).click();

  // The kitchen screen shows it under New, with the note and the deal's contents.
  await page.goto(shopUrl(CODE, "/kitchen"));
  const card = page.getByRole("listitem", { name: `Token ${token}` });
  await expect(card).toBeVisible({ timeout: 15_000 });
  await expect(card.getByText("No onions")).toBeVisible();
  await expect(card.getByText(/Regular fries/)).toBeVisible();
  await expect(card.getByText("Dine-in · Table 9")).toBeVisible();
  await expect(page.getByRole("region", { name: "New" }).getByRole("listitem", { name: `Token ${token}` })).toBeVisible();
  await card.getByRole("button", { name: "Start" }).click();
  await expect(page.getByRole("region", { name: "Preparing" }).getByRole("listitem", { name: `Token ${token}` })).toBeVisible();
  await page.getByRole("region", { name: "Preparing" }).getByRole("listitem", { name: `Token ${token}` }).getByRole("button", { name: "Ready" }).click();
  const ready = page.getByRole("region", { name: "Ready to collect" }).getByRole("listitem", { name: `Token ${token}` });
  await expect(ready).toBeVisible();
  await ready.getByRole("button", { name: "Collected" }).click();
  await expect(page.getByRole("listitem", { name: `Token ${token}` })).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("fast food: menu, recipe, kitchen and stock pages open", async ({ page }) => {
  test.setTimeout(120_000);
  const errors = watchErrors(page);
  await shopSignIn(page, CODE, "owner", DEMO_PASSWORD);
  for (const path of ["/kitchen", "/products", "/stock", "/sales", "/deliveries"]) {
    await page.goto(shopUrl(CODE, path));
    await expect(page.getByRole("heading", { level: 1 }).first(), path).toBeVisible({ timeout: 30_000 });
  }
  // A menu item shows its recipe.
  const id = await page.evaluate(async () => {
    const r = await fetch("/api/v1/products?q=zinger%20burger");
    return ((await r.json()) as { items: { id: number; kind: string }[] }).items.find((p) => p.kind === "RECIPE")?.id;
  });
  expect(id, "demo menu item").toBeTruthy();
  await page.goto(shopUrl(CODE, `/products/${id}`));
  await page.getByRole("tab", { name: "Recipe" }).or(page.getByRole("button", { name: "Recipe" })).first().click();
  await expect(page.getByText("Recipe (per serving)")).toBeVisible();
  await expect(page.getByText("Chicken fillet")).toBeVisible();
  expect(errors).toEqual([]);
});
