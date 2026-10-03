import declaration from "./catalog.json" with { type: "json" };

/** 导航与Owner发布候选消费同一声明；静态入口配置不代表用户持有业务授权。 */
const menus = declaration.menus;
/** 无业务授权含义的组织入口；落地页由本人导航结果选择。 */
export const centralEntry = "/operations";
export const compiledMenus = menus;
const ordered = <T extends { position: number | null; code: string }>(
  values: T[],
) =>
  [...values].sort(
    (a, b) =>
      (a.position ?? 100) - (b.position ?? 100) || a.code.localeCompare(b.code),
  );

/** 稳定code独立于路由，改地址不会偷偷变成新的菜单身份。 */
export const centralGroups = ordered(
  menus.filter(
    (menu) => menu.code.startsWith("group.") && menu.parent === null,
  ),
).map((group) => ({
  key: group.code.slice("group.".length),
  label: group.label ?? group.code,
  pages: ordered(
    menus.filter((menu) => menu.parent === group.code && menu.route !== null),
  ).map((menu) => [menu.route!, menu.label ?? menu.code] as [string, string]),
}));

/** 协作入口也使用声明的实际名称，不在壳层维护第二份文案。 */
export const collaborationMenu = menus.find(
  (menu) => menu.code === "menu.collaboration.products",
)!;
export const centralRoutes = [
  ...centralGroups.flatMap((group) => group.pages.map(([path]) => path)),
  collaborationMenu.route!,
];
