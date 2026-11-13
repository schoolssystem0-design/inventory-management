import { expect, test, type Page } from "@playwright/test";
import { platformPage, platformUrl, shopSignIn, shopUrl, uniqueCode } from "./helpers";

/**
 * The Stage 2 journey end to end: a super admin creates a shop with the wizard, the owner sets a password from the
 * one-time link, signs in, adds a product, adds a cashier, changes a setting, and support opens the shop read-only.
 */
test.describe.serial("shop lifecycle", () => {
  const code = uniqueCode("e2e");
  const ownerPassword = "Kettle-Harbour-2026";
  let setupLink = "";
  let platform: Page;

  test.beforeAll(async ({ browser }) => {
    platform = await platformPage(browser);
  });

  test.afterAll(async () => {
    await platform.context().close();
  });

  test("platform overview loads after TOTP sign-in", async () => {
    await expect(platform.getByRole("heading", { level: 1 })).toBeVisible();
    await platform.getByRole("link", { name: "Shops" }).first().click();
    await expect(platform.getByRole("heading", { name: "Shops", level: 1 })).toBeVisible();
  });

  test("create a shop with the five-step wizard", async () => {
    await platform.goto(platformUrl("/shops/new"));
    // Code first: leaving the name field fills in a suggested code when the code is still empty.
    await platform.getByLabel("Shop code").fill(code);
    await platform.getByLabel("Shop name").fill(`E2E Medical ${code}`);
    await platform.getByLabel("City").fill("Lahore");
    await expect(platform.getByText(`Address: ${code}`)).toBeVisible();
    await platform.getByRole("button", { name: "Next", exact: true }).click();

    // Type: medical store switches on the pharmacy feature as standard.
    await platform.getByRole("button", { name: /Medical store/ }).click();
    await expect(platform.getByText("Features for this shop")).toBeVisible();
    await platform.getByRole("button", { name: "Next", exact: true }).click();

    await platform.getByRole("button", { name: /Starter/ }).click();
    await platform.getByRole("button", { name: "Next", exact: true }).click();
    await platform.getByRole("button", { name: "Next", exact: true }).click();

    await platform.getByLabel("Owner's name").fill("Ayesha Khan");
    await platform.getByRole("button", { name: "Create shop" }).click();

    await expect(platform.getByRole("heading", { name: "Shop created" })).toBeVisible();
    setupLink = await platform.getByLabel("Owner setup link").inputValue();
    expect(setupLink).toContain(`${code}.`);
    expect(setupLink).toContain("/setup");
  });

  test("the code is now taken", async () => {
    await platform.goto(platformUrl("/shops/new"));
    await platform.getByLabel("Shop code").fill(code);
    await platform.getByLabel("Shop name").fill("Another");
    await expect(platform.getByText(/Try /)).toBeVisible();
    await expect(platform.getByRole("button", { name: "Next", exact: true })).toBeDisabled();
  });

  test("owner sets a password from the link and signs in", async ({ page }) => {
    await page.goto(setupLink);
    await expect(page.getByText("Set up your account")).toBeVisible();

    // Weak password is refused with a field message.
    await page.getByLabel("New password").fill("short");
    await page.getByLabel("Type it again").fill("short");
    await page.getByRole("button", { name: /Save|Set password|Continue/ }).click();
    await expect(page.getByLabel("New password")).toHaveAttribute("aria-invalid", "true");

    await page.getByLabel("New password").fill(ownerPassword);
    await page.getByLabel("Type it again").fill(ownerPassword);
    await page.getByRole("button", { name: /Save|Set password|Continue/ }).click();
    await expect(page.getByText("All done")).toBeVisible();

    // The link is single-use.
    await page.goto(setupLink);
    await page.getByLabel("New password").fill("Another-Phrase-2026");
    await page.getByLabel("Type it again").fill("Another-Phrase-2026");
    await page.getByRole("button", { name: /Save|Set password|Continue/ }).click();
    await expect(page.getByText("This link is invalid or has expired. Ask for a new one.")).toBeVisible();

    await shopSignIn(page, code, "owner", ownerPassword);
    await expect(page.getByRole("heading", { name: "Hello, Ayesha" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Get your shop ready" })).toBeVisible();
  });

  test("owner adds a product and finds it", async ({ page }) => {
    await shopSignIn(page, code, "owner", ownerPassword);
    await page.goto(shopUrl(code, "/products/new"));
    await page.getByLabel("Name").first().fill("Panadol 500mg");
    await page.getByLabel("Sale price (Rs)").fill("35.50");
    await page.getByLabel("New barcode").fill("8964000123457");
    await page.getByRole("button", { name: "Add barcode" }).click();
    await page.getByLabel("Formula (generic)").fill("Paracetamol");
    await page.getByRole("button", { name: "Add product" }).click();
    await expect(page.getByText("Panadol 500mg").first()).toBeVisible();

    await page.goto(shopUrl(code, "/products"));
    await expect(page.getByText("Panadol 500mg")).toBeVisible();
    await expect(page.getByText("1 products").or(page.getByText("1 product"))).toBeVisible();

    // Search by the generic formula (medical shops).
    await page.getByLabel("Search products").fill("paracet");
    await expect(page.getByText("Panadol 500mg")).toBeVisible();

    // Duplicate barcode is refused.
    await page.goto(shopUrl(code, "/products/new"));
    await page.getByLabel("Name").first().fill("Copy");
    await page.getByLabel("Sale price (Rs)").fill("10");
    await page.getByLabel("New barcode").fill("8964000123457");
    await page.getByRole("button", { name: "Add barcode" }).click();
    await page.getByRole("button", { name: "Add product" }).click();
    await expect(page.getByText(/already/i).first()).toBeVisible();
  });

  test("labels page renders a barcode for the product", async ({ page }) => {
    await shopSignIn(page, code, "owner", ownerPassword);
    await page.goto(shopUrl(code, "/products/labels"));
    await expect(page.getByRole("heading", { name: "Barcode labels", level: 1 })).toBeVisible();
    await expect(page.getByRole("button", { name: "Print" }).or(page.getByRole("button", { name: /Print/ })).first()).toBeDisabled();
    await page.getByLabel("Find a product").fill("Panadol");
    await page.getByRole("button", { name: /Panadol 500mg/ }).first().click();
    await expect(page.getByLabel("Copies of Panadol 500mg")).toBeVisible();
    // EAN-13 barcode drawn by JsBarcode.
    const barcode = page.getByLabel("Barcode 8964000123457").first();
    await expect(barcode).toBeVisible();
    expect(await barcode.locator("rect").count()).toBeGreaterThan(10);
  });

  test("owner adds a cashier who must change the temporary password", async ({ page, browser }) => {
    await shopSignIn(page, code, "owner", ownerPassword);
    await page.goto(shopUrl(code, "/users"));
    await page.getByRole("button", { name: "New user" }).click();
    await page.getByLabel("Full name").fill("Bilal Cashier");
    await page.getByLabel("Username").fill("bilal");
    await page.getByRole("button", { name: "Save" }).click();
    const dialog = page.getByRole("dialog", { name: "Temporary password for bilal" });
    await expect(dialog).toBeVisible();
    const temp = ((await dialog.locator("p.select-all").textContent()) ?? "").trim();
    expect(temp.length).toBeGreaterThan(7);
    await dialog.getByRole("button", { name: "Done" }).click();
    await expect(page.getByText("Bilal Cashier")).toBeVisible();

    // The cashier signs in with the temporary password and is made to choose a new one; menus are limited.
    const cashier = await browser.newPage();
    await cashier.goto(shopUrl(code, "/login"));
    await cashier.getByLabel("Username").fill("bilal");
    await cashier.getByLabel("Password").fill(temp);
    await cashier.getByRole("button", { name: "Sign in" }).click();
    await expect(cashier.getByRole("heading", { name: "Choose your own password" })).toBeVisible();
    await cashier.getByLabel("Temporary password").fill(temp);
    await cashier.getByLabel("New password").fill("Lantern-Meadow-2026");
    await cashier.getByLabel("Type it again").fill("Lantern-Meadow-2026");
    await cashier.getByRole("button", { name: /Save|Set password|Continue/ }).click();
    await expect(cashier.getByRole("link", { name: "Products" }).first()).toBeVisible();
    await expect(cashier.getByRole("link", { name: "Users" })).toHaveCount(0);

    // Direct navigation to an owner-only page does not show it.
    await cashier.goto(shopUrl(code, "/users"));
    await expect(cashier.getByRole("button", { name: "New user" })).toHaveCount(0);
    await cashier.close();
  });

  test("owner changes a shop setting", async ({ page }) => {
    await shopSignIn(page, code, "owner", ownerPassword);
    await page.goto(shopUrl(code, "/settings"));
    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
    const phone = page.getByLabel("Phone").first();
    await phone.fill("042-3577-0000");
    await page.getByRole("button", { name: /^Save/ }).first().click();
    await expect(page.getByText(/saved/i).first()).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Phone").first()).toHaveValue("042-3577-0000");
  });

  test("support opens the shop read-only", async () => {
    await platform.goto(platformUrl("/shops"));
    await platform.getByText(`E2E Medical ${code}`).click();
    await platform.getByRole("button", { name: "Open as support" }).click();
    await platform.getByLabel("Why are you opening this shop?").fill("E2E check");
    await platform.getByRole("button", { name: "Open shop" }).click();
    await expect(platform.getByText(/Support session/)).toBeVisible();
    await expect(platform.getByText("Read-only")).toBeVisible();
    await expect(platform.getByLabel("Owner's approval code")).toBeVisible();

    // Products are visible, but adding is blocked until the owner gives a code.
    await platform.getByRole("link", { name: "Products" }).first().click();
    await expect(platform.getByText("Panadol 500mg")).toBeVisible();

    await platform.getByRole("button", { name: "Leave support session" }).click();
    await expect(platform).toHaveURL(new RegExp(`admin\\.${ROOT_RE}`));
  });
});

const ROOT_RE = (process.env.E2E_ROOT ?? "localhost:3000").replace(/[.]/g, "\\.");
