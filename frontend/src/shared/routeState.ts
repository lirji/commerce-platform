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
): [string | number, (value: string | number) => void];
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
    sync();
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
      history.state,
      "",
      `${pathRoute ? location.pathname : "#" + page}${query.size ? "?" + query : ""}`,
    );
    setValue(next);
    dispatchEvent(new Event(eventName));
  };
  return [value, update];
}

function queryLocation() {
  const pathRoute =
    location.pathname.startsWith("/operations/") ||
    location.pathname.startsWith("/collaboration/");
  return {
    pathRoute,
    query: new URLSearchParams(
      pathRoute ? location.search : location.hash.split("?")[1],
    ),
  };
}

export type CursorNavigation = { preserveTrail: true };
type SetCursor<T> = (next: T, navigation?: CursorNavigation) => void;

/** 游标与访问轨迹一并保存；不根据 ID 或满页数量推算总页数。 */
export function useCursorState(
  name: string,
  initial: string,
  scope?: string,
): [string, SetCursor<string>];
export function useCursorState(
  name: string,
  initial: number,
  scope?: string,
): [number, SetCursor<number>];
export function useCursorState(
  name: string,
  initial: string | number,
  scope?: string,
): [string | number, SetCursor<string> | SetCursor<number>] {
  const [value] = useRouteState(name, initial);
  const update = (next: string | number, navigation?: CursorNavigation) => {
    const { pathRoute, query } = queryLocation();
    let trail = cursorTrail(query.get(`${name}.previous`), initial);
    let following = cursorTrail(query.get(`${name}.following`), initial);
    const currentRaw = query.get(name) ?? initial;
    const current =
      typeof initial === "number" ? Number(currentRaw) : currentRaw;
    let offset = Number(query.get(`${name}.offset`));
    if (!Number.isSafeInteger(offset) || offset < 0) offset = 0;
    const visited = [...trail, current, ...following];
    const visitedIndex = visited.indexOf(next);

    if (navigation?.preserveTrail && visitedIndex >= 0) {
      trail = visited.slice(0, visitedIndex);
      following = visited.slice(visitedIndex + 1);
    } else if (next === initial) {
      trail = [];
      following = [];
      offset = 0;
    } else if (next !== current) {
      const priorIndex = trail.lastIndexOf(next);
      if (priorIndex >= 0) {
        following = [...trail.slice(priorIndex + 1), current, ...following];
        trail = trail.slice(0, priorIndex);
      } else {
        trail.push(current);
        // 重新读取若拿到不同游标，后续访问轨迹已不再连续。
        following = next === following[0] ? following.slice(1) : [];
      }
    }
    // 分享链接最多保留50个实际访问过的游标。
    if (trail.length > 50) {
      offset += trail.length - 50;
      trail = trail.slice(-50);
    }
    following = following.slice(0, 50 - trail.length);
    if (next === initial) query.delete(name);
    else query.set(name, String(next));
    if (offset) query.set(`${name}.offset`, String(offset));
    else query.delete(`${name}.offset`);
    if (!trail.length) query.delete(`${name}.previous`);
    else query.set(`${name}.previous`, JSON.stringify(trail));
    if (!following.length) query.delete(`${name}.following`);
    else query.set(`${name}.following`, JSON.stringify(following));
    history.replaceState(
      history.state,
      "",
      `${pathRoute ? location.pathname : "#" + location.hash.slice(1).split("?")[0]}${query.size ? "?" + query : ""}`,
    );
    dispatchEvent(new Event(pathRoute ? "popstate" : "hashchange"));
  };
  useEffect(() => {
    if (scope === undefined) return;
    const { pathRoute, query } = queryLocation();
    const key = `${name}.scope`;
    if (query.get(key) === scope) return;
    // 首次分享链接可恢复；更换会员或门店时不能沿用旧记录的游标。
    if (query.has(key)) {
      query.delete(name);
      query.delete(`${name}.previous`);
      query.delete(`${name}.following`);
      query.delete(`${name}.offset`);
    }
    query.set(key, scope);
    history.replaceState(
      history.state,
      "",
      `${pathRoute ? location.pathname : "#" + location.hash.slice(1).split("?")[0]}?${query}`,
    );
    dispatchEvent(new Event(pathRoute ? "popstate" : "hashchange"));
  }, [name, scope]);
  return [value, update];
}

/** URL属于不可信输入，轨迹只接收与游标同类型的短值。 */
export function cursorTrail<T extends string | number>(
  raw: string | null,
  initial: T,
): T[] {
  if (!raw || raw.length > 5000) return [];
  try {
    const value: unknown = JSON.parse(raw);
    return Array.isArray(value) &&
      value.length <= 50 &&
      value.every((v) =>
        typeof initial === "number"
          ? typeof v === "number" && Number.isSafeInteger(v) && v >= initial
          : typeof v === "string" && v.length <= 100,
      )
      ? (value as T[])
      : [];
  } catch {
    return [];
  }
}
