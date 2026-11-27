import { Button, Select, Typography } from "antd";
import {
  cursorTrail,
  useRouteState,
  type CursorNavigation,
} from "./routeState";

/** 仅返回实际访问过的游标，刷新或查看详情后也保留上下文。 */
export function CursorBack<T extends string | number>({
  name,
  after,
  initial,
  onPrevious,
  disabled,
  count,
  pageSize = 50,
  total,
}: {
  name: string;
  after: T;
  initial: T;
  onPrevious: (cursor: T, navigation?: CursorNavigation) => void;
  disabled?: boolean;
  count?: number;
  pageSize?: number;
  total?: number;
}) {
  const [raw] = useRouteState(`${name}.previous`, "");
  const trail = cursorTrail(raw, initial);
  const [offset] = useRouteState(`${name}.offset`, 0);
  const [future] = useRouteState(`${name}.following`, "");
  const following = cursorTrail(future, initial);
  const known = [...trail, after, ...following];
  const position =
    after === initial
      ? 1
      : trail.length && (trail[0] === initial || offset > 0)
        ? offset + trail.length + 1
        : undefined;
  const lastPage =
    total === undefined ? undefined : Math.ceil(total / pageSize);
  return (
    <>
      {count !== undefined && (
        <Typography.Text type="secondary" className="cursor-summary">
          {total === undefined
            ? `本页 ${count} 条 · 每页最多${pageSize}条`
            : `共 ${total.toLocaleString("zh-CN")} 条 · 本页 ${count} 条`}
        </Typography.Text>
      )}
      <Typography.Text type="secondary" className="cursor-position">
        {lastPage === 0
          ? "共 0 页"
          : position === undefined
            ? lastPage === undefined
              ? "续查位置"
              : `续查位置 · 共 ${lastPage} 页`
            : lastPage === undefined
              ? `第${position}页`
              : `第 ${position} / ${lastPage} 页`}
      </Typography.Text>
      <Button
        disabled={disabled || !trail.length}
        onClick={() => {
          const previous = trail.at(-1);
          if (previous !== undefined)
            onPrevious(previous, { preserveTrail: true });
        }}
      >
        上一页
      </Button>
      {position !== undefined && known.length > 1 && (
        <Select
          aria-label="跳转已访问页"
          virtual={false}
          className="cursor-jump"
          placeholder="已访问页"
          value={null}
          disabled={disabled}
          showSearch={{ optionFilterProp: "label" }}
          options={known.map((_, index) => ({
            label: `第 ${offset + index + 1} 页`,
            value: index,
            disabled: index === trail.length,
          }))}
          onChange={(index) => {
            const target = typeof index === "number" ? known[index] : undefined;
            if (target !== undefined)
              onPrevious(target, { preserveTrail: true });
          }}
        />
      )}
    </>
  );
}

export const pageSizeChoices = [10, 25, 50, 100] as const;

export function PageSizeSelect({
  value,
  onChange,
  label = "每页条数",
  disabled,
}: {
  value: number;
  onChange: (value: number) => void;
  label?: string;
  disabled?: boolean;
}) {
  return (
    <Select
      aria-label={label}
      value={value}
      virtual={false}
      disabled={disabled}
      options={pageSizeChoices.map((size) => ({
        label: `每页 ${size} 条`,
        value: size,
      }))}
      onChange={onChange}
    />
  );
}
