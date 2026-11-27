import {
  Button,
  Checkbox,
  Grid,
  Modal,
  Popover,
  Radio,
  Space,
  Table as AntTable,
  Tooltip,
  Typography,
  type TableColumnType,
  type TableProps,
} from "antd";
import { useContext, useLayoutEffect, useRef, useState } from "react";
import { Icon } from "./Icon";

import {
  BusinessTableScope,
  readDisplayPreference,
  writeDisplayPreference,
} from "./businessTableScope";

const copyFields = new Set([
  "businessNo",
  "orderBusinessNo",
  "orderId",
  "skuId",
  "spuId",
  "productId",
  "resourceId",
  "storeId",
  "merchantId",
  "caseId",
  "refundId",
  "grantId",
  "campaignId",
  "batchId",
  "workId",
]);

export function BusinessId({
  value,
  copyOnly = false,
}: {
  value: unknown;
  copyOnly?: boolean;
}) {
  if (typeof value !== "string" || !value)
    return copyOnly ? null : value == null ? "未提供" : String(value);
  return (
    <Typography.Text
      className="business-id"
      title={value}
      copyable={{
        text: value,
        tooltips: ["复制编号", "已复制"],
      }}
    >
      {!copyOnly && value}
    </Typography.Text>
  );
}

function readScroll(key: string) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function saveScroll(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // 浏览器禁用存储时，工具仍在当前页面可用。
  }
}

type BusinessTableProps<Row> = TableProps<Row> & {
  tools?: boolean;
  columnSettings?: boolean;
  onRefresh?: () => void;
  tableId?: string;
  selectedUnit?: string;
};
const tablePageSizes = [7, 10, 25, 50, 100];

export function Table<Row extends object>(props: BusinessTableProps<Row>) {
  const owner = useContext(BusinessTableScope);
  const {
    tools = true,
    columnSettings = true,
    onRefresh,
    tableId,
    selectedUnit = "条",
    ...tableProps
  } = props;
  const columns = tableProps.columns ?? [];
  if (!owner || !tools || columns.length < 2)
    return <AntTable<Row> {...tableProps} />;

  const route = location.hash.split("?")[0] || location.pathname;
  const key = JSON.stringify([
    owner,
    route,
    tableId ??
      columns.map(
        (column) =>
          column.key ??
          ("dataIndex" in column ? column.dataIndex : undefined) ??
          (typeof column.title === "string" ? column.title : ""),
      ),
  ]);
  return (
    <ManagedTable<Row>
      key={key}
      {...tableProps}
      preferenceKey={`commerce.table.${key}`}
      columnSettings={columnSettings}
      onRefresh={onRefresh}
      selectedUnit={selectedUnit}
    />
  );
}

function ManagedTable<Row extends object>({
  preferenceKey,
  columnSettings,
  onRefresh,
  selectedUnit,
  columns = [],
  pagination,
  sticky,
  locale,
  ...props
}: TableProps<Row> & {
  preferenceKey: string;
  columnSettings: boolean;
  selectedUnit: string;
  onRefresh?: () => void;
}) {
  const screens = Grid.useBreakpoint();
  const [density, setDensity] = useState(() =>
    readDisplayPreference(`${preferenceKey}.density`) === "compact"
      ? "compact"
      : "standard",
  );
  const [hidden, setHidden] = useState<string[]>(() => {
    try {
      const value: unknown = JSON.parse(
        readDisplayPreference(`${preferenceKey}.columns`) ?? "[]",
      );
      return Array.isArray(value)
        ? value.filter((item): item is string => typeof item === "string")
        : [];
    } catch {
      return [];
    }
  });
  const [expanded, setExpanded] = useState(false);
  const [pageSize, setPageSize] = useState(() => {
    const saved = Number(readDisplayPreference(`${preferenceKey}.pageSize`));
    return (pagination
      ? (pagination.pageSizeOptions ?? tablePageSizes)
      : tablePageSizes
    ).some((size) => Number(size) === saved)
      ? saved
      : pagination
        ? (pagination.pageSize ?? pagination.defaultPageSize ?? 10)
        : 10;
  });
  const [page, setPage] = useState(() =>
    pagination ? (pagination.current ?? pagination.defaultCurrent ?? 1) : 1,
  );
  const effectivePageSize = pagination
    ? (pagination.pageSize ?? pageSize)
    : pageSize;
  const body = useRef<HTMLDivElement>(null);
  const scrollKey = `${preferenceKey}.scroll`;
  const horizontal = useRef(Number(readScroll(scrollKey)) || 0);
  useLayoutEffect(() => {
    const node = body.current;
    if (expanded || !node || !horizontal.current) return;
    const scroller = node.querySelector<HTMLElement>(
      ".ant-table-body, .ant-table-content",
    );
    if (scroller && scroller.scrollWidth > scroller.clientWidth)
      scroller.scrollLeft = horizontal.current;
  }, [expanded, props.dataSource]);
  const options = columns.map((column, index) => ({
    id: String(
      column.key ??
        ("dataIndex" in column ? column.dataIndex : (column.title ?? index)),
    ),
    label: typeof column.title === "string" ? column.title : undefined,
    locked:
      index === 0 ||
      !!column.fixed ||
      column.title === "操作" ||
      "children" in column,
    column,
  }));
  const optionCounts = new Map<string, number>();
  for (const { id } of options)
    optionCounts.set(id, (optionCounts.get(id) ?? 0) + 1);
  const toggleable = options.filter(
    ({ locked, label, id }) => !locked && label && optionCounts.get(id) === 1,
  );
  const hiddenIds = new Set(hidden);
  const updateColumns = (value: string[]) => {
    setHidden(value);
    writeDisplayPreference(`${preferenceKey}.columns`, JSON.stringify(value));
  };
  const visible = options
    .filter(
      (option) =>
        !columnSettings ||
        !toggleable.includes(option) ||
        !hiddenIds.has(option.id),
    )
    .map(({ column }) => {
      const actionColumn =
        column.title === "操作" &&
        screens.md &&
        props.scroll?.x &&
        typeof column.width === "number" &&
        column.width <= 200 &&
        column.fixed === undefined
          ? { ...column, fixed: "right" as const }
          : column;
      const field = "dataIndex" in column ? column.dataIndex : undefined;
      if (typeof field !== "string" || !copyFields.has(field) || column.render)
        return actionColumn;
      return {
        ...actionColumn,
        render: (value: unknown) => <BusinessId value={value} />,
      } satisfies TableColumnType<Row>;
    });
  const renderTable = (full: boolean) => (
    <AntTable<Row>
      {...props}
      className={`${props.className ?? ""} business-table-${density}`}
      columns={visible}
      sticky={sticky}
      scroll={
        full ||
        (screens.md &&
          props.scroll?.x &&
          !props.scroll.y &&
          (props.dataSource?.length ?? 0) > 12)
          ? {
              ...props.scroll,
              y: full
                ? "min(68dvh, calc(100dvh - 154px))"
                : "min(62dvh, 640px)",
            }
          : props.scroll
      }
      pagination={
        pagination && {
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: tablePageSizes,
          showTotal: (total, range) =>
            total
              ? `共 ${total.toLocaleString("zh-CN")} 条 · 第 ${Math.ceil(range[0] / effectivePageSize)} / ${Math.ceil(total / effectivePageSize)} 页`
              : "共 0 条",
          ...pagination,
          pageSize: effectivePageSize,
          current: pagination.current ?? page,
          onChange: (nextPage, size) => {
            setPage(nextPage);
            if (size !== effectivePageSize) {
              setPageSize(size);
              writeDisplayPreference(`${preferenceKey}.pageSize`, String(size));
            }
            pagination.onChange?.(nextPage, size);
          },
        }
      }
      locale={{
        emptyText: props.loading ? "正在读取记录…" : "当前条件下暂无记录",
        ...locale,
      }}
    />
  );
  return (
    <div
      className="business-table"
      aria-busy={
        typeof props.loading === "object"
          ? Boolean(props.loading.spinning)
          : Boolean(props.loading)
      }
    >
      <div className="business-table-tools" role="group" aria-label="表格工具">
        {!!props.rowSelection?.selectedRowKeys?.length && (
          <Typography.Text className="business-table-selection" role="status">
            已选 {props.rowSelection.selectedRowKeys.length} {selectedUnit}
          </Typography.Text>
        )}
        {onRefresh && (
          <Tooltip title="刷新列表">
            <Button
              icon={<Icon name="refresh" />}
              aria-label="刷新列表"
              loading={Boolean(props.loading)}
              onClick={onRefresh}
            />
          </Tooltip>
        )}
        {columnSettings && toggleable.length > 0 && (
          <Popover
            trigger="click"
            title="显示字段"
            content={
              <div className="business-table-columns">
                {toggleable.map(({ id, label }) => (
                  <Checkbox
                    key={id}
                    checked={!hidden.includes(id)}
                    onChange={(event) =>
                      updateColumns(
                        event.target.checked
                          ? hidden.filter((item) => item !== id)
                          : [...hidden, id],
                      )
                    }
                  >
                    {label}
                  </Checkbox>
                ))}
                <Button
                  type="link"
                  disabled={!hidden.length}
                  onClick={() => updateColumns([])}
                >
                  恢复默认列
                </Button>
              </div>
            }
          >
            <Button icon={<Icon name="columns" />} aria-label="设置表格列">
              列设置
            </Button>
          </Popover>
        )}
        <Popover
          trigger="click"
          title="行距"
          content={
            <Radio.Group
              value={density}
              options={[
                { label: "标准", value: "standard" },
                { label: "紧凑", value: "compact" },
              ]}
              onChange={(event) => {
                const next =
                  event.target.value === "compact" ? "compact" : "standard";
                setDensity(next);
                writeDisplayPreference(`${preferenceKey}.density`, next);
              }}
            />
          }
        >
          <Button icon={<Icon name="density" />} aria-label="调整表格密度">
            行距
          </Button>
        </Popover>
        <Button icon={<Icon name="expand" />} onClick={() => setExpanded(true)}>
          全屏查看
        </Button>
      </div>
      <div
        ref={body}
        hidden={expanded}
        onScrollCapture={(event) => {
          const target = event.target;
          if (
            !(target instanceof HTMLElement) ||
            !target.matches(".ant-table-content, .ant-table-body") ||
            target.scrollWidth <= target.clientWidth ||
            target.scrollLeft === horizontal.current
          )
            return;
          horizontal.current = target.scrollLeft;
          saveScroll(scrollKey, String(target.scrollLeft));
        }}
      >
        {renderTable(false)}
      </div>
      <Modal
        open={expanded}
        title="表格全屏查看"
        onCancel={() => setExpanded(false)}
        footer={
          <Space>
            <Button onClick={() => setExpanded(false)}>返回列表</Button>
          </Space>
        }
        width="calc(100vw - 24px)"
        centered
        destroyOnHidden
        wrapClassName="business-table-dialog"
      >
        {expanded && renderTable(true)}
      </Modal>
    </div>
  );
}
