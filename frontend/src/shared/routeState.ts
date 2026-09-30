import { useCallback, useEffect, useState } from "react";

/** 仅用于可分享的查询状态；表单、凭据和业务命令不能进入URL。 */
export function useRouteState(
  name: string,
  initial: string,
): [string, (value: string) => void];
export function useRouteState(
  name: string,
  initial: number,
): [number, (value: number) => void];
export function useRouteState(
  name: string,
  initial: string | number,
): [string | number, ((value: string) => void) | ((value: number) => void)] {
  const pathRoute =
    location.pathname.startsWith("/operations/") ||
    location.pathname.startsWith("/collaboration/");
  const eventName = pathRoute ? "popstate" : "hashchange";
  const read = useCallback(() => {
    const value = new URLSearchParams(
      pathRoute ? location.search : location.hash.split("?")[1],
    ).get(name);
    if (value == null) return initial;
    if (typeof initial === "number") {
      const number = Number(value);
      return Number.isSafeInteger(number) && number >= 0 ? number : initial;
    }
    return value;
  }, [name, initial, pathRoute]);
  const [value, setValue] = useState(read);
  useEffect(() => {
    const sync = () => setValue(read());
    addEventListener(eventName, sync);
    return () => removeEventListener(eventName, sync);
  }, [read, eventName]);
  const update = (next: string | number) => {
    const [page] = location.hash.slice(1).split("?");
    const query = new URLSearchParams(
      pathRoute ? location.search : location.hash.split("?")[1],
    );
    if (next === initial) query.delete(name);
    else query.set(name, String(next));
    // 筛选/翻页不制造冗长浏览历史，但刷新与进入详情后仍可恢复。
    history.replaceState(
      null,
      "",
      `${pathRoute ? location.pathname : "#" + page}${query.size ? "?" + query : ""}`,
    );
    setValue(next);
    dispatchEvent(new Event(eventName));
  };
  return [value, update];
}
