import { expect, test } from "@playwright/test";

test("同单证据保留可用来源并可单独重查营销", async ({ page }) => {
  const native = "TRD-ORD-261126-0000000101";
  const at = "2026-11-26T10:00:00Z";
  let marketingQueries = 0;
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    let data: unknown = [];
    switch (url.pathname) {
      case "/v1/me":
        data = { tenantId: "pilot", actorId: "admin", role: "ADMIN" };
        break;
      case "/v1/stores":
        data = [
          {
            storeId: "store-1",
            merchantId: "merchant",
            name: "验收店",
            status: "ACTIVE",
          },
        ];
        break;
      case "/v1/runtime-capabilities":
        data = {
          sandboxEnabled: false,
          workersEnabled: false,
          orderAuditEnabled: true,
        };
        break;
      case "/v1/admin/order-operation-evidence": {
        expect(url.searchParams.get("orderNo")).toBe(native);
        marketingQueries += 1;
        const recovery = url.searchParams.get("source") === "MARKETING";
        data = {
          commerce: {
            status: "AVAILABLE",
            data: { businessNo: "报价-01", storeId: "store-1", createdAt: at },
          },
          trade: {
            status: "AVAILABLE",
            data: {
              financialStatus: "REFUNDED",
              receipts: [
                {
                  businessNo: "收款-01",
                  amountMinor: "1000",
                  currency: "CNY",
                  disposition: "NORMAL",
                  confirmedAt: at,
                },
              ],
              refunds: [
                {
                  businessNo: "退款-01",
                  amountMinor: "1000",
                  currency: "CNY",
                  status: "SUCCEEDED",
                  createdAt: at,
                },
              ],
            },
          },
          marketing: recovery
            ? {
                status: "AVAILABLE",
                data: {
                  applicationNo: "资金-01",
                  generation: 7,
                  history: {
                    items: [
                      {
                        businessNo: "资金-01",
                        action: "funding.reverse",
                        result: "SUCCEEDED",
                        currency: "CNY",
                        amountMinor: "1000",
                        occurredAt: at,
                      },
                    ],
                  },
                },
              }
            : { status: "UNAVAILABLE" },
        };
        break;
      }
    }
    await route.fulfill({ json: data });
  });
  await page.goto("/#orders?store=store-1");
  await page.getByLabel("访问凭据", { exact: true }).fill("evidence-fixture");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.getByRole("button", { name: "查询同单证据", exact: true }).click();
  await page.getByLabel("交易订单号", { exact: true }).fill(native);
  const lookup = page.getByLabel("交易订单号", { exact: true });
  await lookup.press("Enter");
  await expect(page.getByText("报价-01")).toBeVisible();
  await expect(page.getByText("收款-01")).toBeVisible();
  await expect(page.getByText("退款-01")).toBeVisible();
  await expect(page.getByText("来源暂不可用")).toBeVisible();
  await page.getByRole("button", { name: "重查营销资金" }).click();
  await expect(page.getByText("资金-01")).toBeVisible();
  await expect(page.getByText("退款-01")).toBeVisible();
  expect(marketingQueries).toBe(2);
});
