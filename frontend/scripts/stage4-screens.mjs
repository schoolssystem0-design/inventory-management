// Captures the Stage 4 screens and a short recording of a sale for the stage report.
// Needs the local API (LocalDevApplication) and the app on :3000. Usage: node scripts/stage4-screens.mjs [outDir]
import { mkdirSync, readdirSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";
import { chromium, devices } from "@playwright/test";

const out = process.argv[2] ?? "../docs/stage-reports/stage-4-screens";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ channel: "chrome" });

async function signIn(page, code) {
  const base = `http://${code}.localhost:3000`;
  await page.goto(base + "/login");
  await page.getByLabel("Username").fill("owner");
  await page.getByLabel("Password").fill("Demo-Shop-2026");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(base + "/");
  return base;
}

async function shot(page, name) {
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log("saved", name);
}

async function openTill(page) {
  const open = page.getByRole("heading", { name: "Open your till" });
  const search = page.getByLabel("Scan or search products");
  await open.or(search).waitFor();
  if (await open.isVisible()) {
    await page.getByLabel("Cash in the drawer (opening float)").fill("5000");
    await page.getByRole("button", { name: "Open till" }).click();
  }
  await search.waitFor();
  await page.getByText(/products ready/).waitFor();
}

// ---- a sale, recorded (super store)
{
  const context = await browser.newContext({ viewport: { width: 1366, height: 820 }, recordVideo: { dir: `${out}/video-tmp`, size: { width: 1366, height: 820 } } });
  const page = await context.newPage();
  const base = await signIn(page, "demo-mart");
  // No print dialog in a headless browser: the receipt is shown on the sale page instead.
  await page.evaluate(() => localStorage.setItem("pos-autoprint", "0"));
  await page.goto(base + "/pos");
  await openTill(page);
  const search = page.getByLabel("Scan or search products");
  await search.pressSequentially("grocery", { delay: 60 });
  await page.waitForTimeout(500);
  await shot(page, "01-pos-search");
  await search.press("Enter");
  await search.fill("P-00002");
  await search.press("Enter");
  await search.fill("3*P-00003");
  await search.press("Enter");
  await page.getByLabel("Bill discount").fill("5%");
  await page.waitForTimeout(600);
  await shot(page, "02-pos-bill");
  await search.focus();
  await page.keyboard.press("F9");
  await page.getByRole("dialog", { name: /Collect/ }).waitFor();
  await page.getByRole("button", { name: "Split payment" }).click();
  await page.waitForTimeout(400);
  await shot(page, "03-pos-split-payment");
  await page.getByRole("dialog").getByRole("button", { name: /Remove payment/ }).last().click().catch(() => {});
  await page.getByRole("dialog").getByRole("button", { name: /^5,000/ }).click().catch(() => {});
  await page.waitForTimeout(500);
  await page.getByRole("button", { name: "Complete sale" }).click();
  await page.getByRole("dialog", { name: /^Sale / }).waitFor();
  await page.waitForTimeout(1200);
  await shot(page, "04-pos-sale-done");
  await page.getByRole("button", { name: /New sale/ }).click();
  await page.waitForTimeout(800);
  await context.close();
  const dir = `${out}/video-tmp`;
  const file = readdirSync(dir).find((f) => f.endsWith(".webm"));
  if (file) renameSync(join(dir, file), `${out}/sale-recording.webm`);
  rmSync(dir, { recursive: true, force: true });
  console.log("saved sale-recording.webm");
}

// ---- desktop pages
{
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  let base = await signIn(page, "demo-mart");
  await shot(page, "05-home-dashboard");
  for (const [path, name] of [
    ["/sales", "06-sales"],
    ["/till", "08-till"],
    ["/customers", "09-customers"],
    ["/stock", "11-stock"],
    ["/stock/alerts", "12-alerts"],
    ["/purchases", "13-purchases"],
    ["/purchases/receive", "14-receive-goods"],
    ["/purchases/reorder", "15-reorder"],
    ["/stock/counts", "16-counts"],
    ["/expenses", "18-expenses"],
    ["/cash", "19-daily-cash"],
    ["/profit", "20-profit-loss"],
    ["/reports", "21-reports"],
  ]) {
    await page.goto(base + path);
    await shot(page, name);
  }
  const firstReport = await page.evaluate(async () => (await (await fetch("/api/v1/reports")).json())[0].key);
  await page.goto(base + "/reports/" + firstReport);
  await shot(page, "22-report");
  await page.goto(base + "/sales");
  await page.locator("tbody tr").first().click();
  await page.getByTitle("Receipt preview").waitFor();
  await shot(page, "07-sale-detail-receipt");
  await page.goto(base + "/customers");
  await page.locator("tbody tr").first().click();
  await shot(page, "10-customer-ledger");
  await page.goto(base + "/stock/counts");
  const row = page.locator("tbody tr").first();
  if (await row.count()) {
    await row.click();
    await shot(page, "17-count-screen");
  }

  base = await signIn(page, "demo-mobile");
  for (const [path, name] of [
    ["/imei", "23-imei-search"],
    ["/buybacks", "24-buybacks"],
    ["/warranty", "25-warranty"],
  ]) {
    await page.goto(base + path);
    await shot(page, name);
  }
  await page.goto(base + "/buybacks");
  await page.getByRole("button", { name: "Buy a used phone" }).click();
  await shot(page, "26-buyback-form");

  base = await signIn(page, "demo-pharmacy");
  await page.goto(base + "/pos");
  await openTill(page);
  await page.getByLabel("Scan or search products").fill("formula 1");
  await shot(page, "27-pharmacy-formula-search");
}

// ---- phone
{
  const page = await (await browser.newContext({ ...devices["Pixel 7"] })).newPage();
  const base = await signIn(page, "demo-mart");
  await page.goto(base + "/pos");
  await openTill(page);
  await page.getByLabel("Scan or search products").fill("P-00001");
  await page.getByLabel("Scan or search products").press("Enter");
  await shot(page, "28-phone-pos");
  await page.goto(base + "/");
  await shot(page, "29-phone-home");
}

await browser.close();
