import { readFileSync } from "node:fs";
import { validateDeclaration } from "./catalog-source.mjs";

/** 构建先拒绝无法由当前壳层完整展示的声明，避免新增路由被导航生成静默遗漏。 */
const declaration = validateDeclaration(
  JSON.parse(
    readFileSync(new URL("../src/iam/catalog.json", import.meta.url), "utf8"),
  ),
);
const menus = new Map(declaration.menus.map((menu) => [menu.code, menu]));
for (const menu of declaration.menus) {
  if (menu.route?.startsWith("/operations/")) {
    const parent = menus.get(menu.parent);
    if (
      !parent?.code.startsWith("group.") ||
      parent.parent !== null ||
      !parent.label ||
      !menu.label
    )
      throw new Error(
        "operational route requires a named top-level navigation group",
      );
  }
}
const collaboration = menus.get("menu.collaboration.products");
if (collaboration?.route !== "/collaboration/products" || !collaboration.label)
  throw new Error("collaboration route declaration required");
process.stdout.write("PASS: complete supported menu declaration\n");
