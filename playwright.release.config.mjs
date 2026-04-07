import { defineConfig } from "@playwright/test";

const ci = process.env.CI === "1" || process.env.CI === "true";

export default defineConfig({
  testDir: "./tools/playwright/test",
  testMatch: /.*seo\.smoke\.spec\.mjs/,
  timeout: 45_000,
  fullyParallel: false,
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  workers: ci ? 1 : undefined,
  outputDir: "tmp/playwright/test-results",
  reporter: ci
    ? [
        ["github"],
        ["html", { open: "never", outputFolder: "tmp/playwright/report" }]
      ]
    : [
        ["list"],
        ["html", { open: "never", outputFolder: "tmp/playwright/report" }]
      ],
  use: {
    baseURL: "http://127.0.0.1:4173",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    viewport: { width: 1440, height: 900 }
  },
  webServer: {
    command:
      "npm run build && PLAYWRIGHT_STATIC_ROOT=out/release-public node tools/playwright/static_server.mjs",
    url: "http://127.0.0.1:4173/",
    reuseExistingServer: false,
    timeout: 120_000
  }
});
