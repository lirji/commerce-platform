import { UserManager, WebStorageStateStore } from "oidc-client-ts";
export const enabled = import.meta.env.VITE_IAM_ENABLED === "true";
export { centralRoutes as routes } from "./navigation";
import { centralRoutes as routes, centralEntry } from "./navigation";
export const manager = enabled
  ? new UserManager({
      authority: import.meta.env.VITE_IAM_AUTHORITY,
      client_id: import.meta.env.VITE_IAM_CLIENT_ID,
      redirect_uri: `${location.origin}/iam/callback`,
      response_type: "code",
      scope: "openid profile",
      userStore: new WebStorageStateStore({ store: sessionStorage }),
      stateStore: new WebStorageStateStore({ store: sessionStorage }),
      automaticSilentRenew: false,
      loadUserInfo: false,
    })
  : null;
/** 回跳只保留固定本应用路由和非授权性的组织上下文，不接受外部URL。 */
export function safeReturn(value: unknown) {
  if (typeof value !== "string") return centralEntry;
  let url: URL;
  try {
    url = new URL(value, location.origin);
  } catch {
    return centralEntry;
  }
  if (
    url.origin !== location.origin ||
    ![centralEntry, ...routes].includes(url.pathname)
  )
    return centralEntry;
  const result = new URL(url.pathname, location.origin);
  for (const name of [
    "tenant_id",
    "environment",
    ...(["/operations/catalog", "/operations/inventory"].includes(url.pathname)
      ? ["store_id"]
      : []),
  ]) {
    const value = url.searchParams.get(name);
    if (value && /^[A-Za-z0-9_-]{1,100}$/.test(value))
      result.searchParams.set(name, value);
  }
  return result.pathname + result.search;
}
let initialization:
  ReturnType<NonNullable<typeof manager>["getUser"]> | undefined;
/** StrictMode重复挂载共享同一次回调处理，授权码只能兑换一次。 */
export function session() {
  if (!manager) return Promise.resolve(null);
  if (!initialization)
    initialization =
      location.pathname === "/iam/callback"
        ? manager
            .signinRedirectCallback()
            .then((user) => {
              history.replaceState(null, "", safeReturn(user.state));
              return user;
            })
            .catch((error) => {
              // 失败回调同样清除授权码，不将一次性凭据残留在地址栏。
              history.replaceState(null, "", centralEntry);
              throw error;
            })
        : manager.getUser();
  return initialization;
}
export async function login() {
  await manager?.signinRedirect({
    state: safeReturn(location.pathname + location.search),
  });
}
/** 清除共享恢复Promise，避免退出后复用首次读取的旧用户。 */
export async function clearSession() {
  initialization = undefined;
  await manager?.removeUser();
}
