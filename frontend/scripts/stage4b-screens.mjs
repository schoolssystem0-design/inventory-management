// Screens of the Stage 4b modules for the stage report.
// Needs the local API (LocalDevApplication, five demo shops) and the app on :3000.
// Usage: node scripts/stage4b-screens.mjs [outDir]
import { mkdirSync } from "node:fs";
import { chromium } from "@playwright/test";

const out = process.argv[2] ?? "../docs/stage-reports/stage-4b-screens";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ channel: "chrome" });
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

async function signIn(code) {
  const base = `http://${code}.localhost:3000`;
  await page.goto(base + "/login");
  await page.getByLabel("Username").fill("owner");
  await page.getByLabel("Password").fill("Demo-Shop-2026");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(base + "/");
  return base;
}

async function shot(name) {
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log("saved", name);
}

async function openTill() {
  const open = page.getByRole("heading", { name: "Open your till" });
  const search = page.getByLabel("Scan or search products");
  await open.or(search).waitFor();
  if (await open.isVisible()) {
    await page.getByLabel("Cash in the drawer (opening float)").fill("5000");
    await page.getByRole("button", { name: "Open till" }).click();
  }
  await page.getByText(/products ready/).waitFor();
}

// ---- super store: offers, coupon at the till, loyalty, scale code
{
  const base = await signIn("demo-mart");
  await page.goto(base + "/promotions");
  await shot("01-offers");
  await page.getByRole("button", { name: "New offer" }).click();
  await shot("02-offer-form");
  await page.keyboard.press("Escape");
  const offer = await page.evaluate(async () => {
    const list = await (await fetch("/api/v1/promotions")).json();
    const buyGet = list.find((o) => o.kind === "BUY_GET");
    return (await (await fetch(`/api/v1/products/${buyGet.productIds[0]}`)).json()).sku;
  });
  await page.evaluate(() => localStorage.setItem("pos-autoprint", "0"));
  await page.goto(base + "/pos");
  await openTill();
  const search = page.getByLabel("Scan or search products");
  await search.fill(`3*${offer}`);
  await search.press("Enter");
  await page.getByRole("button", { name: "Add a coupon" }).click();
  await page.getByLabel("Coupon code").fill("WELCOME10");
  await page.getByRole("button", { name: "Apply" }).click();
  await page.waitForTimeout(600);
  await shot("03-pos-offer-and-coupon");
  const customer = await page.evaluate(async () => (await (await fetch("/api/v1/customers?size=1")).json()).items[0].id);
  await page.goto(`${base}/customers/${customer}`);
  await page.getByRole("tab", { name: "Loyalty points" }).click().catch(() => page.getByText("Loyalty points").click());
  await shot("04-customer-loyalty");
  await page.goto(base + "/settings");
  await page.getByText("Shop-type rules").click();
  await shot("05-settings-shop-type-rules");
}

// ---- mobile: repairs
{
  const base = await signIn("demo-mobile");
  await page.goto(base + "/repairs");
  await shot("06-repairs");
  await page.getByRole("button", { name: "Book a repair" }).click();
  await shot("07-repair-booking");
  await page.keyboard.press("Escape");
  await page.locator("tbody tr").first().click();
  await shot("08-repair-job");
}

// ---- pharmacy: prescriptions and register
{
  const base = await signIn("demo-pharmacy");
  await page.goto(base + "/prescriptions");
  await shot("09-prescriptions");
  await page.goto(base + "/prescriptions/register");
  await shot("10-controlled-register");
}

// ---- electronics: installments and deliveries
{
  const base = await signIn("demo-electronics");
  await page.goto(base + "/installments");
  await shot("11-installments");
  await page.locator("tbody tr").first().click();
  await shot("12-installment-plan");
  await page.goto(base + "/installments/due");
  await shot("13-installments-late");
  await page.goto(base + "/deliveries?status=");
  await shot("14-deliveries");
}

// ---- hardware: challans
{
  const base = await signIn("demo-hardware");
  await page.goto(base + "/challans?status=");
  await shot("15-challans");
  await page.locator("tbody tr").first().click();
  await shot("16-challan");
  await page.goto(base + "/challans/new");
  await shot("17-new-challan");
}

await browser.close();
