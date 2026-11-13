import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end journeys against a running API. Locally: start the API with
 * `cd backend && mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"`
 * then `npm run e2e` (builds and starts the app on :3000). Shops are reached at <code>.localhost:3000 and the
 * platform portal at admin.localhost:3000. To test staging, set E2E_ROOT (e.g. staging.selectcodepos.com).
 */
export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  timeout: 60_000,
  use: {
    trace: "retain-on-failure",
    // Installed Google Chrome: no browser download needed.
    channel: process.env.E2E_CHANNEL ?? "chrome",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], channel: process.env.E2E_CHANNEL ?? "chrome" }, testIgnore: /mobile.spec.ts/ },
    {
      name: "phone",
      use: { ...devices["Pixel 7"], channel: process.env.E2E_CHANNEL ?? "chrome" },
      testMatch: /mobile\.spec\.ts/,
    },
  ],
  webServer: process.env.E2E_ROOT
    ? undefined
    : {
        command: "npm run start",
        url: "http://localhost:3000/",
        reuseExistingServer: true,
        timeout: 180_000,
      },
});
