import { DatePicker } from "antd";
import dayjs from "dayjs";
import customParseFormat from "dayjs/plugin/customParseFormat";
import type { ComponentProps } from "react";

dayjs.extend(customParseFormat);
const localFormat = "YYYY-MM-DDTHH:mm";
const minuteFormats = ["YYYY-MM-DD HH:mm", localFormat];
const instantFormats = ["YYYY-MM-DD HH:mm:ss", ...minuteFormats];

type Props = Pick<
  ComponentProps<typeof DatePicker>,
  "id" | "disabled" | "allowClear" | "onBlur" | "aria-describedby"
> & {
  value?: string;
  instant?: boolean;
  onChange?: (value: string | undefined) => void;
};

/** 保留现有表单的本地分钟字符串，提交处继续按原契约转换时间。 */
export function DateTimeField({
  value,
  onChange,
  instant = false,
  onBlur,
  ...props
}: Props) {
  const selected = value ? dayjs(value) : null;
  const formats = instant ? instantFormats : minuteFormats;
  const serialize = (next: dayjs.Dayjs) =>
    instant ? next.toISOString() : next.format(localFormat);
  return (
    <DatePicker
      {...props}
      className="date-time-field"
      value={selected?.isValid() ? selected : null}
      format={formats}
      showTime={{ format: instant ? "HH:mm:ss" : "HH:mm" }}
      needConfirm={false}
      placeholder="选择日期和时间"
      renderExtraFooter={() => `本机时区 UTC${dayjs().format("Z")}`}
      onBlur={(event, info) => {
        // 点提交会先离开输入框。此时同步写入合法文本，表单无需等待弹层关闭动画。
        if (event.target instanceof HTMLInputElement) {
          const parsed = dayjs(event.target.value.trim(), formats, true);
          const next = parsed.isValid() ? serialize(parsed) : undefined;
          if (next !== value) onChange?.(next);
        }
        onBlur?.(event, info);
      }}
      onChange={(next) => onChange?.(next ? serialize(next) : undefined)}
    />
  );
}

function dateRange(from: dayjs.Dayjs, to: dayjs.Dayjs) {
  return { from: from.format(localFormat), to: to.format(localFormat) };
}

// 与现有筛选契约一致，起点包含、终点不包含，并按本机日历计算。
export const dateRangePresets = [
  {
    label: "今天",
    range: () =>
      dateRange(dayjs().startOf("day"), dayjs().add(1, "day").startOf("day")),
  },
  {
    label: "昨天",
    range: () =>
      dateRange(
        dayjs().subtract(1, "day").startOf("day"),
        dayjs().startOf("day"),
      ),
  },
  {
    label: "近7天",
    range: () =>
      dateRange(
        dayjs().subtract(6, "day").startOf("day"),
        dayjs().add(1, "day").startOf("day"),
      ),
  },
  {
    label: "近30天",
    range: () =>
      dateRange(
        dayjs().subtract(29, "day").startOf("day"),
        dayjs().add(1, "day").startOf("day"),
      ),
  },
  {
    label: "本月",
    range: () =>
      dateRange(
        dayjs().startOf("month"),
        dayjs().add(1, "month").startOf("month"),
      ),
  },
];
