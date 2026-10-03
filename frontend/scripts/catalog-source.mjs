import { createHash } from "node:crypto";

const code = (value) =>
  typeof value === "string" && /^[a-z][a-z0-9._-]{0,99}$/.test(value);
const require = (condition, message) => {
  if (!condition) throw new Error(message);
};
const fields = (value, allowed) =>
  require(value &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).every((key) =>
      allowed.includes(key),
    ), "unknown declaration field");

/** 与治理v1边界一致地验证声明；它是路由配置，不是可缓存授权。 */
export function validateDeclaration(value) {
  fields(value, ["schema_version", "application", "capabilities", "menus"]);
  require(value.schema_version === "1" &&
    code(value.application), "invalid declaration identity");
  require(Array.isArray(value.capabilities) &&
    value.capabilities.length > 0 &&
    value.capabilities.length <= 200, "capability bound");
  require(Array.isArray(value.menus) &&
    value.menus.length <= 100, "menu bound");
  const caps = new Set();
  for (const cap of value.capabilities) {
    fields(cap, ["code", "resource_type", "risk_level"]);
    require(code(cap.code) &&
      cap.code.startsWith(value.application + ".") &&
      code(cap.resource_type) &&
      ["NORMAL", "HIGH"].includes(cap.risk_level) &&
      !caps.has(cap.code), "invalid or duplicate capability");
    caps.add(cap.code);
  }
  const menus = new Map(),
    positions = new Set(),
    routes = new Set();
  for (const menu of value.menus) {
    fields(menu, ["code", "parent", "route", "any_of", "label", "position"]);
    require(code(menu.code) &&
      !menus.has(menu.code) &&
      (menu.parent === null || code(menu.parent)), "invalid or duplicate menu");
    require(Array.isArray(menu.any_of) &&
      menu.any_of.length <= 200 &&
      new Set(menu.any_of).size === menu.any_of.length &&
      menu.any_of.every((cap) =>
        caps.has(cap),
      ), "unknown or duplicate menu capability");
    require(menu.route === null ||
      (typeof menu.route === "string" &&
        /^\/[a-zA-Z0-9/_-]*$/.test(menu.route) &&
        !menu.route.includes("//") &&
        menu.any_of.length > 0 &&
        !routes.has(menu.route)), "invalid or duplicate route");
    if (menu.route !== null) routes.add(menu.route);
    require((menu.label == null) ===
      (menu.position == null), "label and position must be paired");
    if (menu.label != null) {
      require(typeof menu.label === "string" &&
        menu.label.trim() === menu.label &&
        [...menu.label].length > 0 &&
        [...menu.label].length <= 80 &&
        !/[\x00-\x1f\x7f-\x9f<>]/.test(menu.label), "invalid label");
      require(Number.isInteger(menu.position) &&
        menu.position >= 0 &&
        menu.position < 100 &&
        !positions.has(menu.position), "invalid position");
      positions.add(menu.position);
    }
    menus.set(menu.code, menu);
  }
  for (const menu of value.menus) {
    let next = menu;
    const seen = new Set();
    while (next) {
      require(!seen.has(next.code), "menu cycle");
      seen.add(next.code);
      require(next.parent === null || menus.has(next.parent), "missing parent");
      next = next.parent === null ? undefined : menus.get(next.parent);
    }
  }
  require(Buffer.byteLength(JSON.stringify(value)) <=
    131072, "declaration byte bound");
  return value;
}

/** 固定提交和原始声明摘要进入候选，导出过程不登录或产生业务写入。 */
export function candidate(bytes, commit, version, current) {
  require(/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(commit) &&
    Number.isSafeInteger(version) &&
    version > 0, "invalid source or version");
  const declaration = validateDeclaration(JSON.parse(bytes));
  if (current) {
    require(current.application === declaration.application &&
      version > current.manifest_version, "explicit forward version required");
    const caps = new Map(
      declaration.capabilities.map((cap) => [cap.code, cap]),
    );
    require(current.capabilities.every(
      (cap) =>
        caps.has(cap.code) &&
        caps.get(cap.code).resource_type === cap.resource_type &&
        caps.get(cap.code).risk_level === cap.risk_level,
    ), "existing capability meaning must remain");
  }
  return {
    manifest: { ...declaration, manifest_version: version },
    source: {
      commit,
      artifact_hash: createHash("sha256").update(bytes).digest("hex"),
    },
    auto_grants: false,
    auto_roles: false,
  };
}
