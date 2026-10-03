import { Pager, Status, formatField, TableReadHint } from "../shared/ui";
import { useListFilters } from "../shared/listFilters";
import { useCursorState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useMemo, useState, type ReactNode } from "react";
import { RequestContext, useResource, type request } from "../shared/api";
import { ErrorNotice, PageHead, money, time } from "../shared/ui";
import type { Context } from "./api";
import {
  identifier,
  operationsClient,
  type OperationFamily,
} from "./operationsClient";
import { useOperationsCommand, useOperationsLeave } from "./operationsCommand";

export type OperationsProps = {
  context: Context;
  onLogout: () => Promise<void>;
};
export type OperationRow = Record<string, unknown>;
export type Field = {
  name: string;
  label: string;
  kind?: "id" | "number" | "text" | "select" | "time";
  required?: boolean;
  min?: number;
  max?: number;
  options?: string[];
  initial?: unknown;
};
const text = (v: unknown) =>
  v == null ? "—" : typeof v === "boolean" ? (v ? "是" : "否") : String(v);
/** 详情只呈现批准的业务字段；载荷/地址/凭据不通过通用JSON视图泄露。 */
export function OperationDetails({
  row,
  fields,
}: {
  row: OperationRow;
  fields: readonly [string, string][];
}) {
  return (
    <>
      <Descriptions
        column={1}
        bordered
        size="small"
        items={fields.map(([key, label]) => ({
          key,
          label,
          children: formatField(key, row[key]),
        }))}
      />
      {Array.isArray(row.items) && (
        <Table<OperationRow>
          size="small"
          rowKey={(r, i) => `${r.skuId}-${i}`}
          pagination={false}
          scroll={{ x: 480 }}
          dataSource={row.items as OperationRow[]}
          columns={[
            { title: "商品规格", dataIndex: "skuId" },
            { title: "数量", dataIndex: "quantity" },
            {
              title: "单价",
              dataIndex: "unitPrice",
              render: (v) => (v == null ? "—" : money(v)),
            },
            {
              title: "退款金额",
              dataIndex: "refundAmount",
              render: (v) => (v == null ? "—" : money(v)),
            },
          ]}
        />
      )}
    </>
  );
}
export function OperationsWorkspace({
  family,
  title,
  description,
  context,
  onLogout,
  children,
}: {
  family: OperationFamily;
  title: string;
  description: string;
  children: (
    client: typeof request,
    dirty: (key: string, value: boolean) => void,
  ) => ReactNode;
} & OperationsProps) {
  const [expired, setExpired] = useState(false),
    [pending, setPending] = useState<Record<string, boolean>>({});
  const { modal } = App.useApp();
  const client = useMemo(
    () => operationsClient(context, family, () => setExpired(true)),
    [context.token, context.tenant, family],
  );
  const dirty = (key: string, value: boolean) =>
    setPending((previous) => ({ ...previous, [key]: value }));
  const hasPending = Object.values(pending).some(Boolean);
  useOperationsLeave(hasPending);
  async function logout() {
    if (hasPending) {
      modal.warning({
        title: "还有未确认的操作",
        content:
          "请保留当前页面，先核对原目标结果；未知意图不会转换为新的命令。",
      });
      return;
    }
    await onLogout();
  }
  return (
    <RequestContext.Provider value={client}>
      <div className="operations-workspace">
        <PageHead
          title={title}
          eyebrow="中央经营操作"
          description={description}
          extra={
            <Button onClick={logout}>
              {expired ? "重新登录" : "退出登录"}
            </Button>
          }
        />
        {expired && (
          <Alert
            type="warning"
            showIcon
            title="登录已失效"
            description="页面保留原操作意图；重新登录后仍需核对实际结果。"
          />
        )}
        {children(client, dirty)}
      </div>
    </RequestContext.Provider>
  );
}
export function OperationsList({
  path,
  idField,
  columns,
  fields,
  detailPath,
}: {
  path: string;
  idField: string;
  columns: readonly [string, string][];
  fields: readonly [string, string][];
  detailPath?: (id: string) => string;
}) {
  const [after, setAfter] = useCursorState("OperationsWorkspace.after", ""),
    [selected, setSelected] = useState<OperationRow>(),
    [detailId, setDetailId] = useState<string>();
  const filters = useListFilters(`OperationsList.${path}`, path, () =>
    setAfter(""),
  );
  const list = useResource<OperationRow[]>(
      `${path}?after=${encodeURIComponent(after)}&${filters.query}`,
    ),
    detail = useResource<OperationRow>(
      detailId && detailPath ? detailPath(detailId) : null,
    );
  const table: ColumnsType<OperationRow> = columns.map(([key, title]) => ({
    key,
    title,
    dataIndex: key,
    ellipsis: true,
    width: key.endsWith("At")
      ? 180
      : key.toLowerCase().includes("amount") || key === "payable"
        ? 120
        : 150,
    align:
      key.toLowerCase().includes("amount") || key === "payable"
        ? "right"
        : "left",
    render: (v) => formatField(key, v),
  }));
  table.push({
    title: "操作",
    width: 100,
    key: "details",
    render: (_, r) => (
      <Button
        onClick={() => {
          setSelected(r);
          setDetailId(String(r[idField]));
        }}
      >
        查看详情
      </Button>
    ),
  });
  return (
    <Card
      title="当前授权范围内记录"
      extra={
        <Button loading={list.loading} onClick={list.refresh}>
          刷新列表
        </Button>
      }
    >
      {filters.toolbar}
      <ErrorNotice error={list.error} />
      <Table<OperationRow>
        rowKey={(r) => String(r[idField])}
        dataSource={list.data}
        loading={list.loading}
        columns={table}
        scroll={{ x: Math.max(560, columns.length * 150) }}
        pagination={false}
        locale={{
          emptyText: list.error
            ? "读取未完成，请根据错误重新核验"
            : "当前范围暂无记录",
        }}
      />
      <TableReadHint />
      <Pager
        after={after}
        cursorName="OperationsWorkspace.after"
        count={list.data?.length ?? 0}
        pageSize={filters.limit}
        loading={list.loading}
        error={list.error}
        onHome={() => setAfter("")}
        onNext={() => setAfter(String(list.data?.at(-1)?.[idField] ?? ""))}
      />
      <Modal
        className="operations-modal"
        open={!!selected}
        title="业务详情"
        centered
        width={600}
        styles={{ body: { maxHeight: "68vh", overflowY: "auto" } }}
        onCancel={() => {
          setSelected(undefined);
          setDetailId(undefined);
        }}
        footer={
          <Button
            onClick={() => {
              setSelected(undefined);
              setDetailId(undefined);
            }}
          >
            返回列表
          </Button>
        }
      >
        <ErrorNotice error={detail.error} />
        {detailPath && detail.loading ? (
          <Typography.Text>正在核对详情权限…</Typography.Text>
        ) : (
          selected &&
          (!detailPath || detail.data) && (
            <OperationDetails row={detail.data ?? selected} fields={fields} />
          )
        )}
      </Modal>
    </Card>
  );
}
/** 独立动作资格只决定入口提示，提交仍重验实际对象、原键、CAS及资金事实。 */
export function OperationAction({
  client,
  family,
  action,
  label,
  fields = [],
  path,
  body = () => undefined,
  keyed = true,
  dirty,
  onSuccess,
  note,
  readOnly = false,
}: {
  client: typeof request;
  family: OperationFamily;
  action: string;
  label: string;
  fields?: Field[];
  path: (v: Record<string, unknown>) => string;
  body?: (v: Record<string, unknown>) => unknown;
  keyed?: boolean;
  dirty: (key: string, value: boolean) => void;
  onSuccess?: (v: unknown) => void;
  note?: string;
  readOnly?: boolean;
}) {
  const access = useResource<{ allowed: boolean }>(
      `/operations/${family}/${action}-access`,
    ),
    [open, setOpen] = useState(false),
    [changed, setChanged] = useState(false),
    [result, setResult] = useState<unknown>(),
    [formError, setFormError] = useState<Error>();
  const [form] = Form.useForm();
  const { modal } = App.useApp();
  const command = useOperationsCommand(client, family, (v) => dirty(action, v));
  function close() {
    if (command.busy || command.frozen) return;
    const finish = () => {
      setOpen(false);
      setChanged(false);
      dirty(action, false);
      command.clear();
    };
    if (changed) modal.confirm({ title: "放弃尚未提交的输入？", onOk: finish });
    else finish();
  }
  async function submit() {
    if (command.frozen) {
      const value = await command.run(path({}), undefined, action, keyed);
      if (value !== undefined) {
        setResult(value);
        setChanged(false);
        onSuccess?.(value);
      }
      return;
    }
    const v = await form.validateFields();
    const value = await command.run(path(v), body(v), action, keyed);
    if (value !== undefined) {
      setResult(value);
      setChanged(false);
      onSuccess?.(value);
    }
  }
  return (
    <>
      <Space wrap>
        <Button
          disabled={
            access.loading ||
            access.error != null ||
            access.data?.allowed !== true
          }
          onClick={() => {
            setResult(undefined);
            setOpen(true);
            form.resetFields();
          }}
        >
          {label}
        </Button>
        <Button loading={access.loading} onClick={access.refresh}>
          核验{label}资格
        </Button>
      </Space>
      <ErrorNotice error={access.error} />
      <Modal
        className="operations-modal"
        open={open}
        title={label}
        centered
        width={560}
        closable={!command.busy && !command.frozen}
        keyboard={!command.busy && !command.frozen}
        maskClosable={false}
        onCancel={close}
        styles={{ body: { maxHeight: "66vh", overflowY: "auto" } }}
        footer={
          <Space wrap>
            <Button disabled={command.busy || command.frozen} onClick={close}>
              关闭
            </Button>
            <Button
              type="primary"
              loading={command.busy}
              disabled={result !== undefined}
              onClick={() => {
                setFormError(undefined);
                void submit().catch((e) => {
                  if (e instanceof Error) setFormError(e);
                });
              }}
            >
              {command.frozen
                ? "按原意图重试"
                : readOnly
                  ? "读取预览"
                  : "确认操作"}
            </Button>
          </Space>
        }
      >
        <Space orientation="vertical" style={{ width: "100%" }}>
          {note && <Alert type="info" showIcon title={note} />}
          <Alert
            type="info"
            title="提交时将重新核对权限及实际业务事实"
            description="资格提示不承诺任意目标可操作；数据冲突时请重新读取。"
          />
          {command.frozen && (
            <Alert
              type="warning"
              showIcon
              title="结果未知，原目标及输入已冻结"
              description={
                keyed
                  ? "保留当前弹层，重试复用原幂等键；撤权不会释放原意图。"
                  : "保留原目标，再次确认会核对同一笔原渠道事实，资金终态由服务端CAS保护。"
              }
            />
          )}
          <ErrorNotice error={command.error} />
          <ErrorNotice error={formError} />
          <Form
            form={form}
            layout="vertical"
            disabled={command.busy || command.frozen || result !== undefined}
            initialValues={Object.fromEntries(
              fields
                .filter((f) => f.initial !== undefined)
                .map((f) => [f.name, f.initial]),
            )}
            onValuesChange={() => {
              setChanged(true);
              dirty(action, true);
            }}
          >
            {fields.map((f) => (
              <Form.Item
                key={f.name}
                name={f.name}
                label={f.label}
                rules={[
                  {
                    required: f.required !== false,
                    message: `请输入${f.label}`,
                  },
                  ...(f.kind === "id"
                    ? [
                        {
                          pattern: identifier,
                          message: "使用1—64位安全业务编号",
                        },
                      ]
                    : []),
                  ...(f.kind === "number"
                    ? [
                        {
                          type: "integer" as const,
                          min: f.min ?? 0,
                          max: f.max ?? Number.MAX_SAFE_INTEGER,
                        },
                      ]
                    : []),
                ]}
              >
                {f.kind === "number" ? (
                  <InputNumber
                    min={f.min ?? 0}
                    max={f.max ?? Number.MAX_SAFE_INTEGER}
                    style={{ width: "100%" }}
                  />
                ) : f.kind === "select" ? (
                  <Select
                    options={f.options?.map((value) => ({
                      value,
                      label: formatField("mode", value),
                    }))}
                  />
                ) : (
                  <Input
                    maxLength={f.kind === "id" ? 64 : 256}
                    type={f.kind === "time" ? "datetime-local" : "text"}
                  />
                )}
              </Form.Item>
            ))}
          </Form>
          {result !== undefined && <OperationResult value={result} />}
        </Space>
      </Modal>
    </>
  );
}
export function OperationResult({ value }: { value: unknown }) {
  if (typeof value === "number")
    return <Alert type="success" title={`本次已处理 ${value} 项`} />;
  if (!value || typeof value !== "object")
    return <Alert type="success" title="操作已确认" />;
  const r = value as OperationRow;
  if (r.gate && typeof r.gate === "object") {
    const gate = r.gate as OperationRow;
    return (
      <>
        <Alert
          type={gate.allowed ? "success" : "warning"}
          showIcon
          title={gate.allowed ? "安全门允许该只读范围" : "安全门拒绝该范围"}
          description={String(gate.detail ?? "请核对原消费者副作用声明")}
        />
        <Descriptions
          column={1}
          items={[
            ["events", "范围内事件"],
            ["alreadyProcessed", "原已处理"],
            ["wouldExecute", "预计执行"],
            ["limitExceeded", "超过有界上限"],
          ].map(([key, label]) => ({ key, label, children: text(r[key]) }))}
        />
      </>
    );
  }
  return (
    <>
      <Alert
        type="success"
        title={
          r.status
            ? `当前业务状态：${r.status}`
            : r.applied != null
              ? `已恢复 ${r.applied} 项，拒绝 ${r.rejected} 项`
              : r.kind
                ? `业务动作已受理：${r.kind}`
                : "读取已完成"
        }
      />
      <OperationDetails
        row={r}
        fields={[
          ...(
            [
              ["orderId", "订单编号"],
              ["caseId", "售后编号"],
              ["refundId", "退款编号"],
              ["jobId", "任务编号"],
              ["resourceId", "业务对象"],
              ["status", "当前状态"],
              ["version", "业务版本"],
              ["examined", "已核验"],
              ["executed", "已执行"],
              ["failed", "失败数量"],
              ["reason", "处理原因"],
            ] as [string, string][]
          ).filter(([key]) => r[key] != null),
        ]}
      />
    </>
  );
}
