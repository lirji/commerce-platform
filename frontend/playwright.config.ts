import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  workers: 1,
  fullyParallel: false,
  timeout: 90000,
  use: {
    baseURL: process.env.COMMERCE_UI_URL ?? "http://127.0.0.1:8601",
    headless: true,
    actionTimeout: 12000,
    navigationTimeout: 15000,
    viewport: { width: 1440, height: 1000 },
    trace: "off",
    screenshot: "only-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../docs/evidence/s10a/browser-results.json" }],
  ],
  outputDir: "../.local/browser-results",
});
