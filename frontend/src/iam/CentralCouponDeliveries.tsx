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
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ApiError,
  RequestContext,
  useResource,
  type request,
} from "../shared/api";
import { useRouteState } from "../shared/routeState";
import { ErrorNotice, PageHead, initialDate } from "../shared/ui";
import { HTTP, type Context } from "./api";
import {
  DELIVERY_PAGE_SIZE,
  DELIVERY_QUANTUM,
  couponDeliveryClient,
  deliveryIdentifier,
  deliveryControls,
  isDeliveryCreate,
  isDeliveryControl,
  type DeliveryAction,
  type DeliveryCreate,
  type DeliveryView,
  type DeliveryRecipient,
  type DeliveryControl,
} from "./couponDeliveryClient";
import {
  useDeliveryCommand,
  useDeliveryQualification,
} from "./couponDeliveryCommand";

type Dirty = (task: string, value: boolean) => void;
type Props = { context: Context; onLogout: () => Promise<void> };
const labels = {
  create: "创建发券批次",
  control: "控制批次",
  pump: "单次推进",
};
const controlLabels = {
  CANCEL: "取消后续发放",
  RETRY: "恢复隔离任务",
  REVOKE: "撤回可用券",
};
const statusLabels: Record<string, string> = {
  RUNNING: "发放中",
  COMPLETED: "发放完成",
  CANCELLED: "已取消",
  EXPIRED: "已到截止",
  ISOLATED: "已隔离",
  REVOKING: "撤回中",
  REVOCATION_DONE: "撤回处理完成",
  ISSUED: "已发放",
  SKIPPED: "已跳过",
  REVOKED: "已撤回",
  KEPT: "已保留",
};
const idRules = [
  { required: true, message: "请输入实际编号" },
  {
    pattern: deliveryIdentifier,
    message: "使用1—64位字母、数字、下划线、点、冒号或连字符",
  },
];
const integerRules = (minimum: number) => [
  { required: true, message: "请输入版本" },
  {
    validator: (_: unknown, value: number) =>
      Number.isSafeInteger(value) && value >= minimum
        ? Promise.resolve()
        : Promise.reject(
            new Error(minimum ? "版本须为正安全整数" : "版本须为非负安全整数"),
          ),
  },
];
const forbidden = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;
const time = (value: string) => new Date(value).toLocaleString();
function Status({ value }: { value: string }) {
  return (
    <Tag>
      {statusLabels[value] ?? value} · {value}
    </Tag>
  );
}
function Qualification({
  action,
  access,
  recheck,
}: {
  action: DeliveryAction;
  access: ReturnType<typeof useDeliveryQualification>;
  recheck: () => void;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {access.loading && <Spin description="正在核验独立操作权限" />}
      {!access.loading && !access.data?.allowed && (
        <Alert
          type={forbidden(access.error) ? "warning" : "info"}
          title={
            forbidden(access.error)
              ? `当前未获${labels[action]}权限`
              : "操作资格尚未确认"
          }
          description="读取、创建、控制和推进权限分别授予。"
        />
      )}
      {!forbidden(access.error) && <ErrorNotice error={access.error} />}
      <Button disabled={access.loading} onClick={recheck}>
        重新核验{labels[action]}权限
      </Button>
    </Space>
  );
}
function Unknown({ frozen }: { frozen: boolean }) {
  return frozen ? (
    <Alert
      type="warning"
      showIcon
      title="操作结果尚未确认，请保留原意图重试"
      description="原批次、输入和幂等键已锁定。切换页签或返回会保留意图，重新打开后只能原样重试；权限撤销时请联系相关人员核对已有结果。"
    />
  ) : null;
}
/** 冻结后的长编号与原因用只读内容完整换行，不能依靠不可编辑输入框的横向滚动读取。 */
function OriginalInput({ value }: { value?: { path: string; body: unknown } }) {
  if (!value) return null;
  if (isDeliveryCreate(value.body)) {
    const body = value.body;
    return (
      <Descriptions
        title="保留的原输入"
        column={1}
        items={[
          { key: "batch", label: "原批次编号", children: body.batchId },
          { key: "name", label: "批次名称", children: body.name },
          { key: "store", label: "门店编号", children: body.storeId },
          {
            key: "definition",
            label: "固定券定义 / 版本",
            children: `${body.definitionId} / ${body.definitionVersion}`,
          },
          {
            key: "audience",
            label: "固定人群 / 快照版本",
            children: `${body.audience.id} / ${body.audience.version}`,
          },
          {
            key: "deadline",
            label: "发放截止（本地时间）",
            children: time(body.deadline),
          },
          {
            key: "interval",
            label: "会员发券间隔",
            children: `${body.minIntervalHours} 小时`,
          },
        ]}
      />
    );
  }
  if (!isDeliveryControl(value.body)) return null;
  const target = /^\/admin\/coupon-deliveries\/([^/]+)\/control$/.exec(
    value.path,
  );
  return (
    <Descriptions
      title="保留的原输入"
      column={1}
      items={[
        {
          key: "batch",
          label: "原批次编号",
          children: target ? decodeURIComponent(target[1]) : "原目标尚待核对",
        },
        {
          key: "cas",
          label: "原进度 CAS 版本",
          children: value.body.expectedVersion,
        },
        {
          key: "action",
          label: "原控制动作",
          children: controlLabels[value.body.action],
        },
        { key: "reason", label: "原操作原因", children: value.body.reason },
      ]}
    />
  );
}
function BatchDetails({ value }: { value: DeliveryView }) {
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Descriptions
        column={1}
        title="批次与固定来源"
        items={[
          { key: "id", label: "批次编号", children: value.content.batchId },
          { key: "name", label: "名称", children: value.content.name },
          { key: "store", label: "门店编号", children: value.content.storeId },
          {
            key: "definition",
            label: "固定券定义 / 版本",
            children: `${value.content.definitionId} / ${value.content.definitionVersion}`,
          },
          {
            key: "audience",
            label: "固定人群 / 快照版本",
            children: `${value.content.audience.id} / ${value.content.audience.version}`,
          },
          {
            key: "deadline",
            label: "发放截止（本地时间）",
            children: time(value.content.deadline),
          },
          {
            key: "interval",
            label: "会员发券间隔",
            children: `${value.content.minIntervalHours} 小时`,
          },
        ]}
      />
      <Descriptions
        column={1}
        title="实际进度与效果"
        items={[
          {
            key: "status",
            label: "任务状态",
            children: <Status value={value.status} />,
          },
          {
            key: "mode",
            label: "当前方向",
            children: value.mode === "ISSUE" ? "发放 · ISSUE" : "撤回 · REVOKE",
          },
          { key: "version", label: "进度 CAS 版本", children: value.version },
          {
            key: "processed",
            label: "已处理 / 已发放 / 已跳过",
            children: `${value.processed} / ${value.issued} / ${value.skipped}`,
          },
          {
            key: "revoke",
            label: "已撤回 / 已保留",
            children: `${value.revoked} / ${value.kept}`,
          },
          {
            key: "cursor",
            label: "发放检查点",
            children: value.cursorMember || "尚无检查点",
          },
          {
            key: "revokeCursor",
            label: "撤回检查点",
            children: value.revokeCursor || "尚无检查点",
          },
          { key: "attempts", label: "任务尝试次数", children: value.attempts },
          {
            key: "error",
            label: "错误分类",
            children: value.errorCode ?? "无",
          },
        ]}
      />
    </Space>
  );
}
function Receipt({ value }: { value: DeliveryView }) {
  return (
    <Alert
      className="delivery-receipt"
      type="success"
      showIcon
      title="批次回执已确认"
      description={
        <>
          <Typography.Paragraph>
            回执表示该次命令的实际状态，发放完成不表示券当前均可使用；取消不撤回已发券，撤回效果以收件人记录核对。
          </Typography.Paragraph>
          <BatchDetails value={value} />
        </>
      }
    />
  );
}
function CursorControls({
  after,
  count,
  home,
  next,
}: {
  after: string;
  count: number;
  home: () => void;
  next: () => void;
}) {
  return (
    <Space wrap>
      <Typography.Text type="secondary">
        本页 {count} 条，每页最多 {DELIVERY_PAGE_SIZE} 条
      </Typography.Text>
      <Button disabled={!after} onClick={home}>
        返回首批
      </Button>
      <Button disabled={count < DELIVERY_PAGE_SIZE} onClick={next}>
        加载后续
      </Button>
    </Space>
  );
}
function Recipients({ batchId }: { batchId: string }) {
  const [after, setAfter] = useRouteState("DeliveryRecipients.after", "");
  const rows = useResource<DeliveryRecipient[]>(
    deliveryIdentifier.test(batchId)
      ? `/admin/coupon-deliveries/${encodeURIComponent(batchId)}/recipients?after=${encodeURIComponent(after)}&limit=${DELIVERY_PAGE_SIZE}`
      : null,
  );
  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Typography.Title level={5}>实际收件人回执</Typography.Title>
      <Typography.Paragraph type="secondary">
        券编号来自实际发放；已保留可能涉及已使用等状态，不推断为可再次撤回。
      </Typography.Paragraph>
      <Button onClick={rows.refresh}>刷新收件人记录</Button>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <>
          <Table<DeliveryRecipient>
            rowKey="memberId"
            loading={rows.loading}
            dataSource={rows.data ?? []}
            pagination={false}
            scroll={{ x: 850 }}
            columns={[
              { title: "会员编号", dataIndex: "memberId", width: 220 },
              {
                title: "实际结果",
                dataIndex: "status",
                width: 165,
                render: (value: string) => <Status value={value} />,
              },
              {
                title: "券编号",
                dataIndex: "couponId",
                width: 240,
                render: (value: string | null) => value ?? "无",
              },
              {
                title: "错误分类",
                dataIndex: "errorCode",
                width: 160,
                render: (value: string | null) => value ?? "无",
              },
              {
                title: "记录时间",
                dataIndex: "createdAt",
                width: 200,
                render: time,
              },
            ]}
          />
          {!rows.loading && rows.data && (
            <CursorControls
              after={after}
              count={rows.data.length}
              home={() => setAfter("")}
              next={() => setAfter(rows.data!.at(-1)!.memberId)}
            />
          )}
        </>
      )}
    </Space>
  );
}
function Directory({
  revision,
  select,
}: {
  revision: number;
  select: (value: DeliveryView) => void;
}) {
  const [store, setStore] = useRouteState("Delivery.store", ""),
    [after, setAfter] = useRouteState("Delivery.after", "");
  const [detail, setDetail] = useRouteState("Delivery.detail", "");
  const [form] = Form.useForm<{ storeId: string }>(),
    [lookup] = Form.useForm<{ batchId: string }>();
  const rows = useResource<DeliveryView[]>(
    deliveryIdentifier.test(store)
      ? `/admin/coupon-deliveries?storeId=${encodeURIComponent(store)}&after=${encodeURIComponent(after)}&limit=${DELIVERY_PAGE_SIZE}`
      : null,
  );
  useEffect(() => {
    rows.refresh();
  }, [revision]);
  const selected = rows.data?.find((value) => value.content.batchId === detail);
  function open(id: string) {
    const query = new URLSearchParams(location.search);
    query.delete("DeliveryRecipients.after");
    history.replaceState(null, "", `${location.pathname}?${query}`);
    setDetail(id);
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Form
        form={form}
        name="delivery-directory-filter"
        layout="vertical"
        initialValues={{ storeId: store }}
        onFinish={({ storeId }) => {
          setAfter("");
          setDetail("");
          setStore(storeId);
        }}
      >
        <Space wrap align="start">
          <Form.Item name="storeId" label="门店编号" rules={idRules}>
            <Input maxLength={64} placeholder="输入实际门店编号" />
          </Form.Item>
          <Button htmlType="submit" style={{ marginTop: 30 }}>
            查询批次
          </Button>
          <Button
            disabled={!deliveryIdentifier.test(store)}
            onClick={rows.refresh}
            style={{ marginTop: 30 }}
          >
            刷新批次
          </Button>
        </Space>
      </Form>
      {!deliveryIdentifier.test(store) && (
        <Alert
          type="info"
          title="输入门店编号查询批次目录"
          description="目录仅显示实际授权结果；创建、控制和推进可在各自页签独立执行。"
        />
      )}
      <ErrorNotice error={rows.error} />
      {deliveryIdentifier.test(store) && !rows.error && (
        <>
          <Table<DeliveryView>
            rowKey={(value) => value.content.batchId}
            dataSource={rows.data ?? []}
            loading={rows.loading}
            pagination={false}
            scroll={{ x: 1150 }}
            columns={[
              {
                title: "批次",
                width: 270,
                render: (_, value) => (
                  <Space orientation="vertical" size={0}>
                    <Typography.Text>{value.content.name}</Typography.Text>
                    <Typography.Text type="secondary">
                      {value.content.batchId}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: "实际状态",
                dataIndex: "status",
                width: 180,
                render: (value: string) => <Status value={value} />,
              },
              { title: "方向", dataIndex: "mode", width: 100 },
              {
                title: "处理 / 发放 / 跳过",
                width: 180,
                render: (_, value) =>
                  `${value.processed} / ${value.issued} / ${value.skipped}`,
              },
              {
                title: "撤回 / 保留",
                width: 120,
                render: (_, value) => `${value.revoked} / ${value.kept}`,
              },
              { title: "进度 CAS", dataIndex: "version", width: 100 },
              {
                title: "操作",
                width: 150,
                render: (_, value) => (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => open(value.content.batchId)}
                  >
                    详情与收件人
                  </Button>
                ),
              },
            ]}
          />
          {!rows.loading && rows.data && (
            <CursorControls
              after={after}
              count={rows.data.length}
              home={() => {
                setDetail("");
                setAfter("");
              }}
              next={() => {
                setDetail("");
                setAfter(rows.data!.at(-1)!.content.batchId);
              }}
            />
          )}
        </>
      )}
      <Form
        form={lookup}
        name="delivery-recipient-lookup"
        layout="vertical"
        onFinish={({ batchId }) => open(batchId)}
      >
        <Space wrap align="start">
          <Form.Item name="batchId" label="已知批次编号" rules={idRules}>
            <Input maxLength={64} />
          </Form.Item>
          <Button htmlType="submit" style={{ marginTop: 30 }}>
            查询收件人
          </Button>
        </Space>
      </Form>
      <Modal
        centered
        className="campaign-modal delivery-modal"
        title="发券批次与收件人"
        width={1000}
        open={!!detail}
        onCancel={() => setDetail("")}
        mask={{ closable: false }}
        footer={
          <Space wrap>
            <Button onClick={() => setDetail("")}>关闭</Button>
            {selected && (
              <Button
                onClick={() => {
                  setDetail("");
                  select(selected);
                }}
              >
                填入控制目标
              </Button>
            )}
          </Space>
        }
        destroyOnHidden
      >
        {rows.loading && <Spin description="正在读取批次" />}
        {selected ? (
          <BatchDetails value={selected} />
        ) : (
          <Alert
            type="info"
            title="当前目录页未包含该批次详情"
            description="以下按输入的实际批次查询收件人；详情需在正确门店目录中选择。"
          />
        )}
        <Typography.Paragraph className="delivery-id">
          批次：{detail}
        </Typography.Paragraph>
        <Recipients key={detail} batchId={detail} />
      </Modal>
    </Space>
  );
}

type KeyedAction = Exclude<DeliveryAction, "pump">;
type Fields = Partial<DeliveryCreate> & {
  expectedVersion?: number;
  control?: DeliveryControl;
  reason?: string;
};
function Operation({
  action,
  client,
  dirty,
  changed,
  selected,
}: {
  action: KeyedAction;
  client: typeof request;
  dirty: Dirty;
  changed: () => void;
  selected?: DeliveryView;
}) {
  const access = useDeliveryQualification(action),
    [form] = Form.useForm<Fields>(),
    { modal } = App.useApp();
  const [open, setOpen] = useState(false),
    [inputError, setInputError] = useState<Error>();
  const edited = useRef(false);
  const command = useDeliveryCommand(client, (value) => {
    edited.current = value;
    dirty(action, value);
  });
  const [target, setTarget] = useRouteState(`Delivery.${action}.target`, "");
  useEffect(() => {
    if (
      action === "control" &&
      selected &&
      !command.busy &&
      !command.frozen &&
      !edited.current
    ) {
      form.setFieldsValue({
        batchId: selected.content.batchId,
        expectedVersion: selected.version,
      });
      setTarget(selected.content.batchId);
    }
    // 新的目录选择只供空白意图使用，不能覆盖脏表单或结果未知的原目标/CAS。
  }, [selected, action, form]);
  const qualified = !!access.data?.allowed && !access.loading && !access.error;
  const allowed = qualified && !command.error;
  const recheck = () => {
    command.clear();
    access.refresh();
  };
  async function close() {
    if (command.busy) return;
    if (command.frozen) {
      if (
        await modal.confirm({
          centered: true,
          title: "保留原意图并返回页签？",
          content: "原批次、输入和幂等键会保留，重新打开后只能原样重试。",
          okText: "保留并返回",
          cancelText: "继续确认",
        })
      )
        setOpen(false);
      return;
    }
    if (
      edited.current &&
      !(await modal.confirm({
        centered: true,
        title: "放弃未提交的发券输入？",
        okText: "放弃输入",
        cancelText: "继续编辑",
      }))
    )
      return;
    form.resetFields();
    edited.current = false;
    dirty(action, false);
    setInputError(undefined);
    setOpen(false);
  }
  async function submit(value: Fields) {
    if (!allowed || command.busy) return;
    setInputError(undefined);
    let path: string, body: unknown;
    try {
      if (action === "create") {
        path = "/admin/coupon-deliveries";
        body = {
          batchId: value.batchId,
          storeId: value.storeId,
          name: value.name?.trim(),
          definitionId: value.definitionId,
          definitionVersion: value.definitionVersion,
          audience: value.audience,
          deadline: new Date(value.deadline ?? "").toISOString(),
          minIntervalHours: value.minIntervalHours,
        };
        if (!isDeliveryCreate(body))
          throw new Error("请填写有效的固定来源与发放条件");
      } else {
        if (
          !deliveryIdentifier.test(value.batchId ?? "") ||
          !deliveryControls.includes(value.control!) ||
          !Number.isSafeInteger(value.expectedVersion) ||
          value.expectedVersion! < 0 ||
          !value.reason?.trim() ||
          value.reason.length > 256
        )
          throw new Error("请填写实际批次、进度CAS、动作与操作原因");
        path = `/admin/coupon-deliveries/${encodeURIComponent(value.batchId!)}/control`;
        body = {
          expectedVersion: value.expectedVersion,
          action: value.control,
          reason: value.reason.trim(),
        };
      }
    } catch (failure) {
      setInputError(failure as Error);
      return;
    }
    if (await command.run(path, body, action)) {
      setOpen(false);
      form.resetFields();
      changed();
      access.refresh();
    }
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Paragraph>
        {action === "create"
          ? "固定实际券定义与人群快照版本创建批次，提交后按原来源持续核验。"
          : "输入实际批次编号与当前进度 CAS；取消、恢复和撤回的效果分别处理。"}
      </Typography.Paragraph>
      {command.result && <Receipt value={command.result} />}
      <ErrorNotice error={command.error} />
      <Unknown frozen={command.frozen} />
      {command.error instanceof ApiError &&
        command.error.status === HTTP.CONFLICT &&
        !command.frozen && (
          <Alert
            type="warning"
            title="批次版本或状态冲突"
            description="核对实际进度CAS及任务状态，再重新核验权限并调整输入。"
          />
        )}
      <Qualification action={action} access={access} recheck={recheck} />
      {(allowed || command.frozen) && (
        <Button
          type="primary"
          onClick={() => {
            if (
              !command.frozen &&
              !edited.current &&
              deliveryIdentifier.test(target)
            )
              form.setFieldsValue({ batchId: target });
            setOpen(true);
          }}
        >
          {command.frozen ? "恢复原操作意图" : labels[action]}
        </Button>
      )}
      <Modal
        centered
        className="campaign-modal delivery-modal"
        title={labels[action]}
        width={action === "create" ? 780 : 720}
        open={open}
        onCancel={() => void close()}
        closable={!command.busy}
        keyboard={!command.busy}
        mask={{ closable: false }}
        footer={
          <Space wrap>
            <Button disabled={command.busy} onClick={() => void close()}>
              {command.frozen ? "返回页签（保留原意图）" : "取消"}
            </Button>
            <Button disabled={command.busy || access.loading} onClick={recheck}>
              重新核验权限
            </Button>
            {allowed && (
              <Button
                type="primary"
                loading={command.busy}
                onClick={() =>
                  void (command.frozen
                    ? submit(form.getFieldsValue(true))
                    : form.submit())
                }
              >
                {command.frozen ? "原样重试操作" : "确认" + labels[action]}
              </Button>
            )}
          </Space>
        }
      >
        <ErrorNotice error={inputError ?? command.error} />
        <Unknown frozen={command.frozen} />
        <OriginalInput value={command.original} />
        {!qualified && (
          <Qualification action={action} access={access} recheck={recheck} />
        )}
        <Form
          form={form}
          name={`delivery-${action}`}
          hidden={command.frozen}
          layout="vertical"
          disabled={command.busy || command.frozen || !allowed}
          initialValues={{
            definitionVersion: 1,
            audience: { version: 1 },
            minIntervalHours: 24,
            deadline: initialDate(3600),
            expectedVersion: 0,
            control: "CANCEL",
          }}
          onValuesChange={(value) => {
            edited.current = true;
            dirty(action, true);
            if (
              typeof value.batchId === "string" &&
              (value.batchId === "" || deliveryIdentifier.test(value.batchId))
            )
              setTarget(value.batchId);
          }}
          onFinish={(value) => void submit(value)}
        >
          <Form.Item name="batchId" label="实际批次编号" rules={idRules}>
            <Input maxLength={64} />
          </Form.Item>
          {action === "create" ? (
            <>
              <Form.Item
                name="name"
                label="批次名称"
                rules={[{ required: true, whitespace: true, max: 128 }]}
              >
                <Input maxLength={128} />
              </Form.Item>
              <Form.Item name="storeId" label="实际门店编号" rules={idRules}>
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name="definitionId"
                label="固定券定义编号"
                rules={idRules}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name="definitionVersion"
                label="券定义版本"
                rules={integerRules(1)}
              >
                <InputNumber
                  min={1}
                  max={Number.MAX_SAFE_INTEGER}
                  precision={0}
                />
              </Form.Item>
              <Form.Item
                name={["audience", "id"]}
                label="固定人群编号"
                rules={idRules}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name={["audience", "version"]}
                label="人群快照版本"
                rules={integerRules(1)}
              >
                <InputNumber
                  min={1}
                  max={Number.MAX_SAFE_INTEGER}
                  precision={0}
                />
              </Form.Item>
              <Form.Item
                name="deadline"
                label="发放截止（本地时间）"
                rules={[
                  { required: true },
                  {
                    validator: (_, value) => {
                      const remaining = Date.parse(value) - Date.now();
                      return remaining > 0 && remaining <= 7 * 86400000
                        ? Promise.resolve()
                        : Promise.reject(
                            new Error(
                              "截止须在未来七天内，并在固定来源有效期内",
                            ),
                          );
                    },
                  },
                ]}
                extra="按当前设备时区输入。提交时由服务端核验来源有效期及券规则。"
              >
                <Input type="datetime-local" />
              </Form.Item>
              <Form.Item
                name="minIntervalHours"
                label="会员发券间隔（小时）"
                rules={[
                  { required: true },
                  { type: "integer", min: 1, max: 720 },
                ]}
              >
                <InputNumber min={1} max={720} precision={0} />
              </Form.Item>
            </>
          ) : (
            <>
              <Alert
                type="info"
                title="取消不撤回已发券"
                description="撤回仅处理仍可撤回的券，已使用等实际效果会保留。首次撤回独立核验当前权限，恢复任务继续核验该方向的原来源。"
                style={{ marginBottom: 20 }}
              />
              <Form.Item
                name="expectedVersion"
                label="进度 CAS 版本"
                rules={integerRules(0)}
              >
                <InputNumber
                  min={0}
                  max={Number.MAX_SAFE_INTEGER}
                  precision={0}
                />
              </Form.Item>
              <Form.Item
                name="control"
                label="控制动作"
                rules={[{ required: true }]}
              >
                <Select
                  options={deliveryControls.map((value) => ({
                    value,
                    label: controlLabels[value],
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="reason"
                label="操作原因"
                rules={[{ required: true, whitespace: true, max: 256 }]}
              >
                <Input.TextArea maxLength={256} rows={3} />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </Space>
  );
}
function Pump({
  client,
  dirty,
  changed,
}: {
  client: typeof request;
  dirty: Dirty;
  changed: () => void;
}) {
  const access = useDeliveryQualification("pump"),
    { modal } = App.useApp();
  const [busy, setBusy] = useState(false),
    [unknown, setUnknown] = useState(false),
    [error, setError] = useState<Error>(),
    [result, setResult] = useState<number>();
  const running = useRef(false);
  const allowed =
    !!access.data?.allowed && !access.loading && !access.error && !error;
  const recheck = () => {
    setError(undefined);
    access.refresh();
  };
  async function pump() {
    if (running.current || !allowed || unknown) return;
    running.current = true;
    setBusy(true);
    setResult(undefined);
    setError(undefined);
    dirty("pump", true);
    let sent = false;
    try {
      await client<{ allowed: boolean }>(
        "/operations/coupon-deliveries/pump-access",
      );
      sent = true;
      const count = await client<number>("/admin/coupon-deliveries/pump", {
        method: "POST",
      });
      setResult(count);
      dirty("pump", false);
      changed();
    } catch (failure) {
      setError(
        failure instanceof Error ? failure : new Error("推进结果尚未确认"),
      );
      if (sent && (!(failure instanceof ApiError) || failure.status >= 500))
        setUnknown(true);
      else dirty("pump", false);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Paragraph>
        一次仅推进有界任务，最多处理 {DELIVERY_QUANTUM}{" "}
        位收件人，并逐次核验任务原来源。返回数量不表示所有批次已完成；零条也不表示队列为空。
      </Typography.Paragraph>
      <Qualification action="pump" access={access} recheck={recheck} />
      <ErrorNotice error={error} />
      {result !== undefined && (
        <Alert
          type="success"
          title={`本次推进回执已确认：${result}`}
          description="请按实际批次及收件人记录核对发放或撤回效果。"
        />
      )}
      {unknown && (
        <Alert
          type="warning"
          showIcon
          title="本次推进结果未知，可能已提交部分效果"
          description={
            <>
              <Typography.Paragraph>
                该接口没有幂等键。请先查询批次与收件人记录；没有读取权限时请相关人员核对，之后才能发起一次新的有界调用。
              </Typography.Paragraph>
              <Button
                disabled={busy}
                onClick={async () => {
                  if (
                    await modal.confirm({
                      centered: true,
                      title: "确认准备发起下一次有界推进？",
                      content:
                        "先前请求可能已提交部分效果；下一次是新调用，不是原请求的幂等重试。请先核对已有结果。",
                      okText: "已核对，允许下一次",
                      cancelText: "暂不推进",
                    })
                  ) {
                    setUnknown(false);
                    dirty("pump", false);
                    recheck();
                  }
                }}
              >
                核对后允许下一次推进
              </Button>
            </>
          }
        />
      )}
      {allowed && !unknown && (
        <Button type="primary" loading={busy} onClick={() => void pump()}>
          执行单次推进
        </Button>
      )}
    </Space>
  );
}
function Panels({ client, dirty }: { client: typeof request; dirty: Dirty }) {
  const [tab, setTab] = useRouteState("workspaceTab", "directory"),
    [revision, setRevision] = useState(0),
    [selected, setSelected] = useState<DeliveryView>();
  const changed = () => setRevision((value) => value + 1);
  return (
    <Tabs
      activeKey={
        ["create", "control", "pump"].includes(tab) ? tab : "directory"
      }
      onChange={setTab}
      destroyOnHidden={false}
      items={[
        {
          key: "directory",
          label: "批次与收件人",
          forceRender: true,
          children: (
            <Directory
              revision={revision}
              select={(value) => {
                setSelected(value);
                setTab("control");
              }}
            />
          ),
        },
        {
          key: "create",
          label: labels.create,
          forceRender: true,
          children: (
            <Operation
              action="create"
              client={client}
              dirty={dirty}
              changed={changed}
            />
          ),
        },
        {
          key: "control",
          label: labels.control,
          forceRender: true,
          children: (
            <Operation
              action="control"
              client={client}
              dirty={dirty}
              changed={changed}
              selected={selected}
            />
          ),
        },
        {
          key: "pump",
          label: labels.pump,
          forceRender: true,
          children: <Pump client={client} dirty={dirty} changed={changed} />,
        },
      ]}
    />
  );
}
export function CentralCouponDeliveries({ context, onLogout }: Props) {
  const [expired, setExpired] = useState(false),
    tasks = useRef(new Set<string>()),
    { modal } = App.useApp();
  const client = useMemo(
    () => couponDeliveryClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (tasks.current.size) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const dirty: Dirty = (task, value) => {
    if (value) tasks.current.add(task);
    else tasks.current.delete(task);
  };
  const logout = () => {
    if (!tasks.current.size) void onLogout();
    else
      modal.confirm({
        centered: true,
        title: "离开发券工作区？",
        content:
          "未保存输入或未知结果将丢失。请先原样重试有幂等键的命令，或核对未知推进结果。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  return (
    <main className="central-products">
      <Card>
        <PageHead
          eyebrow="营销资产"
          title="定向发券"
          description="固定来源版本，核对批次进度及实际发放、撤回效果。"
          extra={<Button onClick={logout}>退出登录</Button>}
        />
        {expired ? (
          <Alert
            type="warning"
            title="登录已失效，请重新登录"
            action={<Button onClick={() => void onLogout()}>返回登录</Button>}
          />
        ) : (
          <RequestContext.Provider value={client}>
            <Panels client={client} dirty={dirty} />
          </RequestContext.Provider>
        )}
      </Card>
    </main>
  );
}
