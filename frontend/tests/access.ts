import { resolve } from "node:path";

/** 独立验收目录避免覆盖其他任务的本地凭据；CI 沿用原默认位置。 */
export function accessPath(name: string) {
  return process.env.COMMERCE_ACCESS_DIR
    ? resolve(process.env.COMMERCE_ACCESS_DIR, name)
    : new URL(`../../.local/${name}`, import.meta.url);
}
