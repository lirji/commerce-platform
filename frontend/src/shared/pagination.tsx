import { Button, Typography } from "antd";
import { cursorTrail, useRouteState } from "./routeState";

/** 仅返回实际访问过的游标，刷新或查看详情后也保留上下文。 */
export function CursorBack<T extends string | number>({
  name,
  after,
  initial,
  onPrevious,
  disabled,
  count,
  pageSize = 50,
}: {
  name: string;
  after: T;
  initial: T;
  onPrevious: (cursor: T) => void;
  disabled?: boolean;
  count?: number;
  pageSize?: number;
}) {
  const [raw] = useRouteState(`${name}.previous`, "");
  const trail = cursorTrail(raw, initial);
  const [offset] = useRouteState(`${name}.offset`, 0);
  return (
    <>
      {count !== undefined && (
        <Typography.Text type="secondary">
          本页 {count} 条 · 每页最多{pageSize}条
        </Typography.Text>
      )}
      <Typography.Text type="secondary" className="cursor-position">
        {after === initial
          ? "第1页"
          : trail.length && (trail[0] === initial || offset > 0)
            ? `第${offset + trail.length + 1}页`
            : "续查位置"}
      </Typography.Text>
      <Button
        disabled={disabled || !trail.length}
        onClick={() => {
          const previous = trail.at(-1);
          if (previous !== undefined) onPrevious(previous);
        }}
      >
        上一页
      </Button>
    </>
  );
}
