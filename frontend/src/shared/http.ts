/** 页面读取与中央客户端共用协议状态，身份拒绝和业务冲突保持不同恢复含义。 */
export const HTTP = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  CONFLICT: 409,
  UNAVAILABLE: 503,
} as const;
