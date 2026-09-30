const SESSION_KEY = "commerce.session.v1";
export const SESSION_EXPIRED = "commerce:session-expired";

/** 只保留当前标签页凭据；身份和权限始终在刷新后由服务端重新核验。 */
export function savedCredential(): string {
  try {
    return sessionStorage.getItem(SESSION_KEY) ?? "";
  } catch {
    return "";
  }
}

/** 浏览器禁用存储时仍可内存登录，调用方告知刷新恢复不可用。 */
export function saveCredential(value: string): boolean {
  try {
    if (value) sessionStorage.setItem(SESSION_KEY, value);
    else sessionStorage.removeItem(SESSION_KEY);
    return true;
  } catch {
    return false;
  }
}
