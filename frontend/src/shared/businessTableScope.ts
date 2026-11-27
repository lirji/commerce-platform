import { createContext } from "react";

/** 用组织和登录主体隔离列表展示偏好，会员端继续使用原展示。 */
export const BusinessTableScope = createContext<string | undefined>(undefined);

export function readDisplayPreference(key: string) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeDisplayPreference(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 浏览器禁用存储时，当前组件状态仍可用。
  }
}
