import { defineConfig } from "@playwright/test";

const ci = process.env.CI === "1" || process.env.CI === "true";
const baseURL = process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8080";
const reuseExistingServer = process.env.PLAYWRIGHT_REUSE_EXISTING_SERVER === "true";
const webServerCommand =
  process.env.PLAYWRIGHT_WEB_SERVER_COMMAND || "npm run dev:browser-inspection";

export default defineConfig({
  testDir: "./tools/playwright/test",
  testMatch: /.*\.spec\.mjs/,
  testIgnore: /.*seo\.smoke\.spec\.mjs/,
  globalSetup: "./tools/playwright/global_setup_interactive.mjs",
  timeout: 45_000,
  fullyParallel: false,
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  workers: ci ? 1 : undefined,
  outputDir: "tmp/playwright/test-results/interactive",
  reporter: ci
    ? [
        ["github"],
        ["html", { open: "never", outputFolder: "tmp/playwright/report/interactive" }]
      ]
    : [
        ["list"],
        ["html", { open: "never", outputFolder: "tmp/playwright/report/interactive" }]
      ],
  use: {
    baseURL,
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    viewport: { width: 1440, height: 900 }
  },
  webServer: {
    command: webServerCommand,
    url: baseURL,
    reuseExistingServer,
    timeout: 120_000
  }
});
