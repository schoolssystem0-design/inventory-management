import { createHmac } from "node:crypto";
import { existsSync, mkdirSync } from "node:fs";
import { expect, type Browser, type Page } from "@playwright/test";

/** Root domain the app is served on: shops at <code>.<root>, the platform at admin.<root>. */
export const ROOT = process.env.E2E_ROOT ?? "localhost:3000";
const SCHEME = process.env.E2E_SCHEME ?? (ROOT.startsWith("localhost") ? "http" : "https");

export const shopUrl = (code: string, path = "/") => `${SCHEME}://${code}.${ROOT}${path}`;
export const platformUrl = (path = "/") => `${SCHEME}://admin.${ROOT}${path}`;

/** Local dev seed (backend LocalDevApplication). Override for staging with env vars. */
export const ADMIN = {
  email: process.env.E2E_ADMIN_EMAIL ?? "admin@local.test",
  password: process.env.E2E_ADMIN_PASSWORD ?? "Admin-Local-2026",
  totpSecret: process.env.E2E_ADMIN_TOTP ?? "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
};
export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? "Demo-Shop-2026";

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), matching the backend's Totp. */
export function totp(secret: string, at = Date.now()): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/=+$/, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, "0");
}

export async function platformSignIn(page: Page) {
  await page.goto(platformUrl("/login"));
  await page.getByLabel("Email").fill(ADMIN.email);
  await page.getByLabel("Password").fill(ADMIN.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  const code = page.getByLabel("Authenticator code");
  await code.waitFor();
  await code.fill(totp(ADMIN.totpSecret));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(platformUrl("/"));
}

export async function shopSignIn(page: Page, code: string, username: string, password: string) {
  await page.goto(shopUrl(code, "/login"));
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(shopUrl(code, "/"));
}

/** A code that will not clash with earlier runs. */
export const uniqueCode = (prefix: string) => `${prefix}-${Date.now().toString(36)}`;

const PLATFORM_STATE = ".auth/platform.json";

/**
 * A signed-in platform page. The session is saved and reused while it is valid, because platform sign-in is limited
 * to 10 attempts per 5 minutes per address (each sign-in uses 2: password, then code).
 */
export async function platformPage(browser: Browser): Promise<Page> {
  if (existsSync(PLATFORM_STATE)) {
    const page = await (await browser.newContext({ storageState: PLATFORM_STATE })).newPage();
    await page.goto(platformUrl("/"));
    await page.waitForLoadState("networkidle");
    if (!page.url().endsWith("/login")) return page;
    await page.context().close();
  }
  const page = await (await browser.newContext()).newPage();
  await platformSignIn(page);
  mkdirSync(".auth", { recursive: true });
  await page.context().storageState({ path: PLATFORM_STATE });
  return page;
}
