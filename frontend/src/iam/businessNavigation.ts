import { useEffect, useRef, useState } from "react";
import { CentralError, HTTP, invalidateSession, type Context } from "./api";
import { compiledMenus } from "./navigation";

export const deploymentEnvironment =
  import.meta.env?.VITE_IAM_ENVIRONMENT ?? "test";
const MAX_MENUS = 100;
const MAX_BYTES = 131072;
const MAX_CAPABILITIES = 200;
export const NavigationState = {
  AVAILABLE: "AVAILABLE",
  NO_ACCESS: "NO_ACCESS",
} as const;
const HUMAN_ACTOR = "HUMAN";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const hash = /^[0-9a-f]{64}$/;
export type NavigationMenu = {
  code: string;
  parent: string | null;
  route: string | null;
  label: string | null;
  position: number | null;
};
export type NavigationView = {
  schemaVersion: string;
  requestId: string;
  context: {
    principalId: string;
    membershipId: string;
    membershipGeneration: number;
    membershipVersion: number;
    principalVersion: number;
    tenantId: string;
    applicationId: string;
    environment: string;
    callerServiceId: string;
    actorType: string;
    traceId: string;
  };
  manifestVersion: number;
  contentHash: string;
  presentationHash: string;
  observedAt: string;
  state: (typeof NavigationState)[keyof typeof NavigationState];
  menus: NavigationMenu[];
  capabilityHints: string[];
};

/** 浏览器再核对当前上下文；服务端SDK仍负责完整权威协议及身份验证。 */
export function validateNavigation(
  value: unknown,
  tenant: string,
  environment: string,
): NavigationView {
  const view = value as NavigationView;
  const context = view?.context;
  const positive = (number: unknown) =>
    Number.isSafeInteger(number) && (number as number) > 0;
  if (
    !view ||
    view.schemaVersion !== "1" ||
    !uuid.test(view.requestId) ||
    !context ||
    context.tenantId !== tenant ||
    context.applicationId !== "commerce" ||
    context.environment !== environment ||
    context.actorType !== HUMAN_ACTOR ||
    !uuid.test(context.principalId) ||
    !uuid.test(context.membershipId) ||
    !positive(context.membershipGeneration) ||
    !positive(context.membershipVersion) ||
    !positive(context.principalVersion) ||
    !positive(view.manifestVersion) ||
    !hash.test(view.contentHash) ||
    !hash.test(view.presentationHash) ||
    !Array.isArray(view.menus) ||
    view.menus.length > MAX_MENUS ||
    !Array.isArray(view.capabilityHints) ||
    view.capabilityHints.length > MAX_CAPABILITIES ||
    !Object.values(NavigationState).includes(view.state) ||
    (view.state === NavigationState.NO_ACCESS &&
      (view.menus.length || view.capabilityHints.length)) ||
    (view.state === NavigationState.AVAILABLE && !view.menus.length)
  )
    throw new CentralError(HTTP.UNAVAILABLE);
  const age = Date.now() - Date.parse(view.observedAt);
  if (
    !Number.isFinite(age) ||
    age < -5000 ||
    age > 31000 ||
    !view.observedAt.endsWith("Z")
  )
    throw new CentralError(HTTP.UNAVAILABLE);
  const menus = new Map<string, NavigationMenu>();
  for (const menu of view.menus) {
    if (
      !menu ||
      typeof menu.code !== "string" ||
      menus.has(menu.code) ||
      (menu.parent !== null && typeof menu.parent !== "string") ||
      (menu.route !== null && typeof menu.route !== "string") ||
      (menu.label !== null &&
        (typeof menu.label !== "string" ||
          !menu.label.trim() ||
          menu.label.length > 80)) ||
      (menu.label === null) !== (menu.position === null) ||
      (menu.position !== null &&
        (!Number.isInteger(menu.position) ||
          menu.position < 0 ||
          menu.position >= MAX_MENUS))
    )
      throw new CentralError(HTTP.UNAVAILABLE);
    menus.set(menu.code, menu);
  }
  for (const menu of view.menus) {
    const seen = new Set([menu.code]);
    let parent = menu.parent;
    while (parent !== null) {
      const ancestor = menus.get(parent);
      if (!ancestor || seen.has(parent))
        throw new CentralError(HTTP.UNAVAILABLE);
      seen.add(parent);
      parent = ancestor.parent;
    }
  }
  if (
    new Set(view.capabilityHints).size !== view.capabilityHints.length ||
    view.capabilityHints.some(
      (code) => typeof code !== "string" || !code.startsWith("commerce."),
    )
  )
    throw new CentralError(HTTP.UNAVAILABLE);
  return view;
}

export type NavigationItem = {
  code: string;
  label: string;
  route?: string;
  children: NavigationItem[];
};
export type NavigationModel = {
  items: NavigationItem[];
  groups: { key: string; label: string; pages: [string, string][] }[];
  routes: Map<string, string>;
  first?: string;
};

/** code与已编译route同时匹配才可执行；未知部署页面不会借目录提示被放开。 */
export function navigationModel(view?: NavigationView): NavigationModel {
  const compiled = new Map(compiledMenus.map((menu) => [menu.code, menu]));
  const ordered = [...(view?.menus ?? [])].sort(
    (a, b) =>
      (a.position ?? MAX_MENUS) - (b.position ?? MAX_MENUS) ||
      a.code.localeCompare(b.code),
  );
  const routes = new Map<string, string>();
  const build = (parent: string | null): NavigationItem[] =>
    ordered
      .filter((menu) => menu.parent === parent)
      .flatMap((menu) => {
        const declaration = compiled.get(menu.code);
        const route =
          menu.route &&
          declaration?.route === menu.route &&
          (menu.route.startsWith("/operations/") ||
            menu.route === "/collaboration/products")
            ? menu.route
            : undefined;
        const children = build(menu.code);
        const label = menu.label ?? declaration?.label ?? menu.code;
        if (route) routes.set(route, label);
        return route || children.length
          ? [{ code: menu.code, label, route, children }]
          : [];
      });
  const items = build(null);
  const pages = (item: NavigationItem): [string, string][] => [
    ...(item.route ? [[item.route, item.label] as [string, string]] : []),
    ...item.children.flatMap(pages),
  ];
  const groups = items.map((item) => ({
    key: item.code,
    label: item.label,
    pages: pages(item),
  }));
  return {
    items,
    groups,
    routes,
    first: groups.flatMap((group) => group.pages)[0]?.[0],
  };
}

/** 环境不随URL换目标；只有当前部署固定分区的本人接口可以读取。 */
export async function loadNavigation(
  context: Context,
  environment: string,
  generation?: number,
  signal?: AbortSignal,
) {
  if (environment !== deploymentEnvironment)
    throw new CentralError(HTTP.FORBIDDEN);
  const query = generation
    ? `?expected_membership_generation=${generation}`
    : "";
  const response = await fetch(`/v1/operations/navigation${query}`, {
    headers: {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    },
    signal: signal
      ? AbortSignal.any([signal, AbortSignal.timeout(15000)])
      : AbortSignal.timeout(15000),
    redirect: "error",
    cache: "no-store",
  });
  if (!response.ok) {
    invalidateSession(response.status);
    throw new CentralError(response.status);
  }
  const body = await response.text();
  if (new TextEncoder().encode(body).length > MAX_BYTES)
    throw new CentralError(HTTP.UNAVAILABLE);
  return validateNavigation(JSON.parse(body), context.tenant, environment);
}

/** 同上下文刷新沿用已知代际；身份／组织变化立即隔离旧视图与迟到响应。 */
export function useBusinessNavigation(context: Context, environment: string) {
  const key = JSON.stringify([context.token, context.tenant, environment]);
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<{
    key: string;
    loading: boolean;
    view?: NavigationView;
    error?: CentralError;
  }>({ key, loading: true });
  const generation = useRef<{ key: string; value: number } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setState({ key, loading: true });
    Promise.resolve()
      .then(() =>
        controller.signal.aborted
          ? undefined
          : loadNavigation(
              context,
              environment,
              generation.current?.key === key
                ? generation.current.value
                : undefined,
              controller.signal,
            ),
      )
      .then((view) => {
        if (!controller.signal.aborted && view) {
          generation.current = {
            key,
            value: view.context.membershipGeneration,
          };
          setState({ key, view, loading: false });
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({
            key,
            loading: false,
            error:
              error instanceof CentralError
                ? error
                : new CentralError(HTTP.UNAVAILABLE),
          });
      });
    return () => controller.abort();
  }, [key, context.token, context.tenant, environment, revision]);
  return {
    ...(state.key === key ? state : { key, loading: true }),
    refresh: () => {
      setState({ key, loading: true });
      setRevision((value) => value + 1);
    },
  };
}
