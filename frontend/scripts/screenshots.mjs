// Captures the main Stage 2 screens for the stage report (desktop and phone).
// Needs the local API (LocalDevApplication) and `npm run start` running. Usage: node scripts/screenshots.mjs <outDir>
import { createHmac } from "node:crypto";
import { mkdirSync } from "node:fs";
import { chromium, devices } from "@playwright/test";

const out = process.argv[2] ?? "../docs/stage-reports/stage-2-screens";
mkdirSync(out, { recursive: true });

function totp(secret) {
  const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret) bits += a.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const ctr = Buffer.alloc(8);
  ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac("sha1", key).update(ctr).digest();
  const o = h[19] & 0xf;
  return String((((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1e6).padStart(6, "0");
}

const browser = await chromium.launch({ channel: "chrome" });

async function shot(page, name) {
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log("saved", name);
}

// Shop (desktop)
{
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  const base = "http://demo-pharmacy.localhost:3000";
  await page.goto(base + "/login");
  await shot(page, "01-shop-login");
  await page.getByLabel("Username").fill("owner");
  await page.getByLabel("Password").fill("Demo-Shop-2026");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(base + "/");
  await shot(page, "02-shop-home");
  for (const [path, name] of [
    ["/products", "03-products"],
    ["/products/new", "04-product-form"],
    ["/products/import", "05-import"],
    ["/products/bulk-price", "06-bulk-price"],
    ["/products/labels", "07-labels"],
    ["/settings", "08-settings"],
    ["/users", "09-users"],
    ["/roles", "10-roles"],
    ["/security", "11-activity"],
  ]) {
    await page.goto(base + path);
    await shot(page, name);
  }
  await page.keyboard.press("Control+k");
  await page.getByLabel("Search products or pages").fill("set");
  await shot(page, "12-search");
}

// Platform (desktop)
{
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  const base = "http://admin.localhost:3000";
  await page.goto(base + "/login");
  await page.getByLabel("Email").fill("admin@local.test");
  await page.getByLabel("Password").fill("Admin-Local-2026");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Authenticator code").fill(totp("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(base + "/");
  await shot(page, "20-platform-overview");
  await page.goto(base + "/shops");
  await shot(page, "21-platform-shops");
  await page.goto(base + "/shops/new");
  await page.getByLabel("Shop name").fill("Al-Noor Pharmacy");
  await page.getByLabel("Shop code").fill("alnoor-pharmacy");
  await page.getByRole("button", { name: "Next" }).click();
  await page.getByRole("button", { name: /Medical store/ }).click();
  await shot(page, "22-platform-wizard-type");
  await page.goto(base + "/types");
  await shot(page, "23-platform-types");
  await page.goto(base + "/plans");
  await shot(page, "24-platform-plans");
}

// Phone
{
  const page = await (await browser.newContext({ ...devices["Pixel 7"] })).newPage();
  const base = "http://demo-mart.localhost:3000";
  await page.goto(base + "/login");
  await page.getByLabel("Username").fill("owner");
  await page.getByLabel("Password").fill("Demo-Shop-2026");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(base + "/");
  await shot(page, "30-phone-home");
  await page.goto(base + "/products");
  await shot(page, "31-phone-products");
  await page.goto(base + "/products/new");
  await shot(page, "32-phone-product-form");
}

await browser.close();
