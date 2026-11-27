import { DatePicker } from "antd";
import dayjs from "dayjs";
import type { ComponentProps } from "react";

const localFormat = "YYYY-MM-DDTHH:mm";

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
  ...props
}: Props) {
  const selected = value ? dayjs(value) : null;
  return (
    <DatePicker
      {...props}
      className="date-time-field"
      value={selected?.isValid() ? selected : null}
      format={
        instant
          ? ["YYYY-MM-DD HH:mm:ss", "YYYY-MM-DD HH:mm", localFormat]
          : ["YYYY-MM-DD HH:mm", localFormat]
      }
      showTime={{ format: instant ? "HH:mm:ss" : "HH:mm" }}
      needConfirm={false}
      placeholder="选择日期和时间"
      renderExtraFooter={() => `本机时区 UTC${dayjs().format("Z")}`}
      onChange={(next) =>
        onChange?.(instant ? next?.toISOString() : next?.format(localFormat))
      }
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
