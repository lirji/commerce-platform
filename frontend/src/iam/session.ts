import { UserManager, WebStorageStateStore } from "oidc-client-ts";
export const enabled = import.meta.env.VITE_IAM_ENABLED === "true";
export const routes = ["/operations/products", "/collaboration/products"];
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
  if (typeof value !== "string") return "/operations/products";
  const url = new URL(value, location.origin);
  if (url.origin !== location.origin || !routes.includes(url.pathname))
    return "/operations/products";
  const result = new URL(url.pathname, location.origin);
  for (const name of ["tenant_id", "environment"]) {
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
        ? manager.signinRedirectCallback().then((user) => {
            history.replaceState(null, "", safeReturn(user.state));
            return user;
          })
        : manager.getUser();
  return initialization;
}
export async function login() {
  await manager?.signinRedirect({
    state: safeReturn(location.pathname + location.search),
  });
}
