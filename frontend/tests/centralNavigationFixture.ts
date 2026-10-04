import { compiledMenus } from "../src/iam/navigation";

/** 仅供公开DTO浏览器边界，显式提供本人导航；不证明真实SSO或后台权限。 */
export function centralNavigationFixture(tenant: string) {
  return {
    schemaVersion: "1",
    requestId: tenant,
    context: {
      principalId: tenant,
      membershipId: tenant,
      membershipGeneration: 1,
      membershipVersion: 1,
      principalVersion: 1,
      tenantId: tenant,
      applicationId: "commerce",
      environment: "test",
      actorType: "HUMAN",
      callerServiceId: "ui-fixture",
      traceId: tenant,
    },
    manifestVersion: 1,
    contentHash: "a".repeat(64),
    presentationHash: "b".repeat(64),
    observedAt: new Date().toISOString(),
    state: "AVAILABLE",
    menus: compiledMenus.map(({ any_of: _binding, ...menu }) => menu),
    capabilityHints: ["commerce.product.read"],
  };
}
