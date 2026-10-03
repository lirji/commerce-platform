import { expect, test } from "@playwright/test";
import {
  navigationModel,
  validateNavigation,
  type NavigationView,
} from "../src/iam/businessNavigation";
import { compiledMenus } from "../src/iam/navigation";

const tenant = "11111111-1111-4111-8111-111111111111";
const uuid = "22222222-2222-4222-8222-222222222222";
/** 仅协议单测fixture；真实授权／SSO由独立新库的浏览器链路验证。 */
function fixture(): NavigationView {
  return {
    schemaVersion: "1",
    requestId: uuid,
    context: {
      principalId: uuid,
      membershipId: uuid,
      membershipGeneration: 1,
      membershipVersion: 1,
      principalVersion: 1,
      tenantId: tenant,
      applicationId: "commerce",
      environment: "test",
      callerServiceId: "fixture",
      actorType: "HUMAN",
      traceId: uuid,
    },
    manifestVersion: 1,
    contentHash: "a".repeat(64),
    presentationHash: "b".repeat(64),
    observedAt: new Date().toISOString(),
    state: "AVAILABLE",
    menus: compiledMenus
      .filter((menu) =>
        [
          "group.catalog",
          "menu.operations.products",
          "menu.collaboration.products",
        ].includes(menu.code),
      )
      .map(({ any_of: _binding, ...menu }) => menu),
    capabilityHints: ["commerce.product.read"],
  };
}
test("实际稳定ID交集与发布名称／排序决定导航，祖先无链接", () => {
  const view = fixture();
  view.menus.find((menu) => menu.code === "menu.operations.products")!.label =
    "发布后的商品名称";
  const model = navigationModel(validateNavigation(view, tenant, "test"));
  expect(model.first).toBe("/operations/products");
  expect(model.items[0].route).toBeUndefined();
  expect(model.routes.get("/operations/products")).toBe("发布后的商品名称");
  expect(model.routes.has("/operations/orders")).toBe(false);
  expect(model.routes.has("/collaboration/products")).toBe(true);
});
test("未知部署路由、错code同route、祖先原页面及遗留入口不可执行", () => {
  const view = fixture();
  view.menus[0].route = "/admin";
  view.menus.find((menu) => menu.code === "menu.operations.products")!.route =
    "/operations/new-products";
  view.menus.find((menu) => menu.code === "menu.collaboration.products")!.code =
    "unknown.collaboration";
  expect(
    navigationModel(validateNavigation(view, tenant, "test")).routes.size,
  ).toBe(0);
});
test("无权限与当前编译不支持区分，缺失／未知状态不能成为允许", () => {
  const view = fixture();
  view.state = "NO_ACCESS";
  view.menus = [];
  view.capabilityHints = [];
  expect(
    navigationModel(validateNavigation(view, tenant, "test")).first,
  ).toBeUndefined();
  expect(() =>
    validateNavigation({ ...view, state: "PENDING" }, tenant, "test"),
  ).toThrow();
  expect(() =>
    validateNavigation({ ...view, menus: fixture().menus }, tenant, "test"),
  ).toThrow();
});
test("旧上下文、过期时点与坏版本拒绝，不伪装空结果", () => {
  for (const value of [
    { ...fixture(), context: { ...fixture().context, tenantId: uuid } },
    {
      ...fixture(),
      context: { ...fixture().context, environment: "production" },
    },
    {
      ...fixture(),
      context: { ...fixture().context, membershipGeneration: 0 },
    },
    { ...fixture(), observedAt: new Date(Date.now() - 60000).toISOString() },
    { ...fixture(), contentHash: "bad" },
  ])
    expect(() => validateNavigation(value, tenant, "test")).toThrow();
});
test("重复、缺失祖先、循环和协议超量拒绝", () => {
  const duplicate = fixture();
  duplicate.menus.push(duplicate.menus[0]);
  const missing = fixture();
  missing.menus.find((menu) => menu.parent !== null)!.parent = "missing";
  const cyclic = fixture();
  cyclic.menus.find((menu) => menu.code === "group.catalog")!.parent =
    "menu.operations.products";
  const oversized = fixture();
  oversized.menus = Array.from({ length: 101 }, (_, index) => ({
    ...oversized.menus[0],
    code: `item.${index}`,
  }));
  for (const view of [duplicate, missing, cyclic, oversized])
    expect(() => validateNavigation(view, tenant, "test")).toThrow();
});
