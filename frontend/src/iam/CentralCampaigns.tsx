import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
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
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  ApiError,
  RequestContext,
  useResource,
  type request,
} from "../shared/api";
import { useRouteState } from "../shared/routeState";
import { ErrorNotice, PageHead } from "../shared/ui";
import { HTTP, type Context } from "./api";
import {
  CampaignDraftForm,
  buildCampaignDraft,
  campaignIdRules,
  positiveVersionRules,
  type DraftFields,
} from "./CampaignDraftForm";
import {
  useCampaignCommand,
  useCampaignQualification,
} from "./campaignCommand";
import {
  CAMPAIGN_PAGE_SIZE,
  campaignClient,
  type CampaignAction,
  type CampaignBudget,
  type CampaignPreview,
  type CampaignPreviewResult,
  type CampaignView,
} from "./campaignClient";

const time = (value: string) => new Date(value).toLocaleString();
const labels = {
  create: "创建草稿",
  preview: "只读预览",
  submit: "提交审批",
  approve: "审批通过",
  reject: "审批拒绝",
  publish: "发布活动",
  pause: "暂停活动",
};
type Props = { context: Context; onLogout: () => Promise<void> };
type Target = { campaignId: string; version: number; expectedVersion: number };
type MarkDirty = (task: string, value: boolean) => void;
const forbidden = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;

function Result({ value }: { value: CampaignView }) {
  return (
    <Alert
      type="success"
      showIcon
      title="操作结果已确认"
      description={
        <Descriptions
          column={1}
          items={[
            {
              key: "name",
              label: "活动",
              children: `${value.content.name} · ${value.content.campaignId}`,
            },
            {
              key: "version",
              label: "内容版本（不可变）",
              children: value.content.version,
            },
            { key: "status", label: "实际状态", children: value.status },
            { key: "lock", label: "状态锁版本", children: value.lockVersion },
          ]}
        />
      }
    />
  );
}
function Unknown({ frozen }: { frozen: boolean }) {
  return frozen ? (
    <Alert
      type="warning"
      showIcon
      title="操作结果尚未确认，请保留原意图重试"
      description="输入、目标和幂等键已锁定。切换页签会保留本次意图，重试确认后再发起新操作；权限撤销时请联系管理员核对已有结果。"
    />
  ) : null;
}
function HintNotice({
  action,
  loading,
  error,
  allowed,
  refresh,
  showRefresh = true,
}: {
  action: CampaignAction;
  loading: boolean;
  error?: Error;
  allowed: boolean;
  refresh: () => void;
  showRefresh?: boolean;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {loading && <Spin description="正在核验独立操作权限" />}
      {!loading && !allowed && (
        <Alert
          type={forbidden(error) ? "warning" : "info"}
          title={
            forbidden(error)
              ? `当前未获${labels[action]}权限`
              : "操作资格尚未确认"
          }
          description="目录读取和其他动作权限独立授予。"
        />
      )}
      {!forbidden(error) && <ErrorNotice error={error} />}
      {showRefresh && (
        <Button disabled={loading} onClick={refresh}>
          重新核验{labels[action]}权限
        </Button>
      )}
    </Space>
  );
}
function Directory({
  revision,
  select,
}: {
  revision: number;
  select: (value: CampaignView) => void;
}) {
  const [after, setAfter] = useRouteState("CampaignDirectory.after", "");
  const rows = useResource<CampaignView[]>(
    `/admin/campaigns?after=${encodeURIComponent(after)}&limit=${CAMPAIGN_PAGE_SIZE}`,
  );
  useEffect(() => {
    if (revision) rows.refresh();
  }, [revision, rows.refresh]);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        每个活动显示最新内容版本。内容版本定位活动规则，状态锁版本用于审批、发布和暂停的并发校验。
      </Typography.Text>
      <Button onClick={rows.refresh}>刷新活动目录</Button>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<CampaignView>
          rowKey={(row) => row.content.campaignId}
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 1180 }}
          columns={[
            {
              title: "活动",
              width: 240,
              render: (_, row) => (
                <Space orientation="vertical" size={0}>
                  <Typography.Text strong>{row.content.name}</Typography.Text>
                  <Typography.Text type="secondary">
                    {row.content.campaignId}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: "门店",
              width: 150,
              render: (_, row) => row.content.storeId,
            },
            {
              title: "内容版本",
              width: 100,
              render: (_, row) => row.content.version,
            },
            { title: "状态锁版本", dataIndex: "lockVersion", width: 110 },
            {
              title: "状态",
              dataIndex: "status",
              width: 130,
              render: (value) => <Tag>{value}</Tag>,
            },
            {
              title: "开始时间",
              width: 190,
              render: (_, row) => time(row.content.validFrom),
            },
            {
              title: "结束时间",
              width: 190,
              render: (_, row) => time(row.content.validTo),
            },
            {
              title: "版本操作",
              width: 110,
              render: (_, row) => (
                <Button type="link" onClick={() => select(row)}>
                  选择此版本
                </Button>
              ),
            },
          ]}
        />
      )}
      <Space wrap>
        <Button disabled={!after} onClick={() => setAfter("")}>
          回到首页
        </Button>
        <Button
          disabled={
            !!rows.error ||
            rows.loading ||
            rows.data?.length !== CAMPAIGN_PAGE_SIZE
          }
          onClick={() => setAfter(rows.data?.at(-1)?.content.campaignId ?? "")}
        >
          下一页
        </Button>
      </Space>
    </Space>
  );
}
function Create({
  client,
  dirty,
  created,
}: {
  client: typeof request;
  dirty: MarkDirty;
  created: () => void;
}) {
  const access = useCampaignQualification("create");
  const [form] = Form.useForm<DraftFields>(),
    { modal } = App.useApp();
  const [open, setOpen] = useState(false),
    [inputError, setInputError] = useState<Error>();
  const changed = useRef(false);
  const command = useCampaignCommand(client, (value) => {
    changed.current = value;
    dirty("create", value);
  });
  // 资格与提交结果分别展示；提交失败仍由allowed关闭动作，避免重复错误提示。
  const qualified = !!access.data?.allowed && !access.loading && !access.error;
  const allowed = qualified && !command.error;
  const recheck = () => {
    command.clear();
    access.refresh();
  };
  const close = async () => {
    if (command.busy) return;
    if (command.frozen) {
      if (
        await modal.confirm({
          title: "保留原意图并返回页签？",
          content:
            "结果未知的输入和幂等键会保留在本页，重新打开后只能原样重试。",
          okText: "保留并返回",
          cancelText: "继续确认",
        })
      )
        setOpen(false);
      return;
    }
    if (
      changed.current &&
      !(await modal.confirm({
        title: "放弃未保存的活动草稿？",
        content: "关闭后清空当前输入。",
        okText: "放弃草稿",
        cancelText: "继续编辑",
      }))
    )
      return;
    form.resetFields();
    changed.current = false;
    dirty("create", false);
    setOpen(false);
    setInputError(undefined);
  };
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      {command.result && <Result value={command.result} />}
      <ErrorNotice error={command.error} />
      {command.error instanceof ApiError &&
        command.error.status === HTTP.CONFLICT &&
        !command.frozen && (
          <Alert
            type="warning"
            title="活动版本已存在或命令输入冲突，请重新核验后纠正编号与版本"
          />
        )}
      <Unknown frozen={command.frozen} />
      <HintNotice
        action="create"
        loading={access.loading}
        error={access.error}
        allowed={qualified}
        refresh={recheck}
      />
      {allowed && (
        <Button type="primary" onClick={() => setOpen(true)}>
          {command.frozen ? "恢复原创建意图" : "创建活动草稿"}
        </Button>
      )}
      <Modal
        centered
        width={640}
        className="campaign-modal"
        title="创建活动草稿"
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
              重新核验创建权限
            </Button>
            {allowed && (
              <Button
                type="primary"
                loading={command.busy}
                onClick={() => form.submit()}
              >
                {command.frozen ? "原样重试创建" : "保存活动草稿"}
              </Button>
            )}
          </Space>
        }
      >
        <ErrorNotice error={inputError ?? command.error} />
        <Unknown frozen={command.frozen} />
        <HintNotice
          action="create"
          loading={access.loading}
          error={access.error}
          allowed={qualified}
          refresh={recheck}
          showRefresh={false}
        />
        <div hidden={!allowed}>
          <CampaignDraftForm
            form={form}
            disabled={command.busy || command.frozen}
            onChange={() => {
              changed.current = true;
              dirty("create", true);
            }}
            onFinish={async (value) => {
              setInputError(undefined);
              let body;
              try {
                body = buildCampaignDraft(value);
              } catch (error) {
                setInputError(error as Error);
                return;
              }
              if (await command.run("/admin/campaigns", body, "create")) {
                setOpen(false);
                form.resetFields();
                created();
                access.refresh();
              } else if (forbidden(command.error)) access.refresh();
            }}
          />
        </div>
      </Modal>
    </Space>
  );
}
const reasons: Record<string, string> = {
  OUTSIDE_VALIDITY: "不在有效期内",
  OUTSIDE_PRODUCT_SCOPE: "不在商品范围",
  BELOW_MINIMUM: "未达到消费门槛",
  CONDITION_NO_MATCH: "资格条件不匹配",
  CONDITION_UNKNOWN: "资格事实未知",
  ELIGIBLE: "符合优惠条件",
  OUTRANKED_BEST_OF: "被更优活动替代",
};
function PreviewResult({ result }: { result: CampaignPreviewResult }) {
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Descriptions
        title="实际预览结果"
        column={1}
        items={[
          { key: "gross", label: "原价（元）", children: result.gross },
          { key: "discount", label: "优惠（元）", children: result.discount },
          { key: "payable", label: "应付（元）", children: result.payable },
          {
            key: "selected",
            label: "实际选中活动",
            children: result.selected
              ? `${result.selected.campaignId} · v${result.selected.version}`
              : "未选中活动",
          },
        ]}
      />
      <Alert
        type="info"
        title="只读预览，未生成报价或预占库存、预算"
        description={result.notice}
      />
      <Table
        rowKey="skuId"
        pagination={false}
        dataSource={result.lines}
        scroll={{ x: 540 }}
        columns={[
          { title: "SKU", dataIndex: "skuId" },
          { title: "原价（元）", dataIndex: "gross" },
          { title: "优惠（元）", dataIndex: "discount" },
          { title: "应付（元）", dataIndex: "payable" },
        ]}
      />
      <Typography.Title level={5}>资格与竞争决策</Typography.Title>
      <Table
        rowKey={(row) => `${row.campaignId}:${row.version}`}
        pagination={false}
        dataSource={result.trace}
        scroll={{ x: 600 }}
        columns={[
          { title: "活动", dataIndex: "campaignId" },
          { title: "内容版本", dataIndex: "version" },
          {
            title: "判定",
            dataIndex: "reason",
            render: (value) => reasons[value] ?? value,
          },
        ]}
      />
      <Typography.Title level={5}>固定人群来源与新鲜度</Typography.Title>
      <Table
        rowKey={(row) => `${row.audienceId}:${row.version}`}
        pagination={false}
        dataSource={result.sources}
        scroll={{ x: 950 }}
        columns={[
          { title: "人群", dataIndex: "audienceId" },
          { title: "版本", dataIndex: "version" },
          { title: "来源", dataIndex: "source" },
          { title: "数据时点", dataIndex: "watermark", render: time },
          { title: "有效截止", dataIndex: "validUntil", render: time },
          { title: "匹配", dataIndex: "match" },
        ]}
      />
    </Space>
  );
}
function Operations({
  client,
  selected,
  dirty,
  changed,
  targetBlocked,
}: {
  client: typeof request;
  selected?: CampaignView;
  dirty: MarkDirty;
  changed: () => void;
  targetBlocked: (value: boolean) => void;
}) {
  const [action, setAction] =
    useState<Exclude<CampaignAction, "create">>("preview");
  const [form] = Form.useForm<Target>(),
    [previewForm] = Form.useForm<CampaignPreview>();
  const [open, setOpen] = useState(false),
    [previewBusy, setPreviewBusy] = useState(false),
    [previewResult, setPreviewResult] = useState<CampaignPreviewResult>(),
    [previewError, setPreviewError] = useState<Error>();
  const access = useCampaignQualification(action);
  const command = useCampaignCommand(client, (value) =>
    dirty("operation", value),
  );
  const runningPreview = useRef(false);
  const blocked = command.busy || command.frozen || previewBusy;
  useEffect(() => {
    targetBlocked(blocked);
  }, [blocked, targetBlocked]);
  useEffect(() => {
    if (selected && !blocked)
      form.setFieldsValue({
        campaignId: selected.content.campaignId,
        version: selected.content.version,
        expectedVersion: selected.lockVersion,
      });
  }, [selected, form]);
  // 资格与提交结果分别展示；提交失败仍由allowed关闭动作，避免重复错误提示。
  const qualified = !!access.data?.allowed && !access.loading && !access.error;
  const allowed = qualified && !command.error && !previewError;
  const recheck = () => {
    command.clear();
    setPreviewError(undefined);
    access.refresh();
  };
  const path = () => {
    const value = form.getFieldsValue();
    return `/admin/campaigns/${encodeURIComponent(value.campaignId)}/${value.version}/${action}`;
  };
  async function preview(value: CampaignPreview) {
    if (runningPreview.current || !allowed) return;
    runningPreview.current = true;
    setPreviewBusy(true);
    setPreviewError(undefined);
    setPreviewResult(undefined);
    dirty("preview", true);
    try {
      const hint = await client<{ allowed: boolean }>(
        "/operations/campaigns/preview-access",
      );
      if (!hint.allowed) throw new ApiError(HTTP.FORBIDDEN, "当前未获预览权限");
      const quantity = new Map<string, number>();
      for (const item of value.items)
        quantity.set(
          item.skuId,
          (quantity.get(item.skuId) ?? 0) + item.quantity,
        );
      if ([...quantity.values()].some((count) => count > 10000))
        throw new Error("合并后的每项SKU数量不能超过10000");
      setPreviewResult(
        await client<CampaignPreviewResult>(path(), {
          method: "POST",
          body: {
            ...value,
            ...(value.at
              ? { at: new Date(value.at).toISOString() }
              : { at: null }),
          },
        }),
      );
      dirty("preview", false);
    } catch (error) {
      setPreviewError(error as Error);
      if (forbidden(error as Error)) access.refresh();
    } finally {
      runningPreview.current = false;
      setPreviewBusy(false);
    }
  }
  const { modal } = App.useApp();
  const close = async () => {
    if (command.busy || previewBusy) return;
    if (
      command.frozen &&
      !(await modal.confirm({
        title: "保留原意图并返回页签？",
        content: "目标、请求体和原键仍保留，重新打开后只能原样重试。",
        okText: "保留并返回",
        cancelText: "继续确认",
      }))
    )
      return;
    setOpen(false);
  };
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="填写实际内容版本，再选择独立操作"
        description="仅持有写入或预览权限时可直接填写目标。状态锁版本是expectedVersion，并非内容版本；对象归属、有效期和状态由服务端再次核验。"
      />
      {command.result && <Result value={command.result} />}
      <ErrorNotice error={command.error ?? previewError} />
      <Unknown frozen={command.frozen} />
      {command.error instanceof ApiError &&
        command.error.status === HTTP.CONFLICT &&
        !command.frozen && (
          <Alert
            type="warning"
            title="状态或锁版本冲突，请核对目标与实际状态后修正输入"
          />
        )}
      <Form
        form={form}
        name="campaign-target"
        layout="vertical"
        disabled={blocked}
        onValuesChange={() => dirty("operation", true)}
        initialValues={{ version: 1, expectedVersion: 0 }}
        onFinish={() => {
          setPreviewResult(undefined);
          setPreviewError(undefined);
          setOpen(true);
        }}
      >
        <div className="node-grid">
          <Form.Item
            name="campaignId"
            label="实际活动编号"
            rules={campaignIdRules}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="version"
            label="内容版本（不可变）"
            rules={positiveVersionRules}
          >
            <InputNumber min={1} max={Number.MAX_SAFE_INTEGER} precision={0} />
          </Form.Item>
          {action !== "preview" && (
            <Form.Item
              name="expectedVersion"
              label="状态锁版本（expectedVersion）"
              rules={[
                {
                  required: true,
                  type: "integer",
                  min: 0,
                  max: Number.MAX_SAFE_INTEGER,
                },
              ]}
            >
              <InputNumber
                min={0}
                max={Number.MAX_SAFE_INTEGER}
                precision={0}
              />
            </Form.Item>
          )}
        </div>
        <Form.Item label="本次操作">
          <Select
            aria-label="本次操作"
            value={action}
            options={(
              [
                "preview",
                "submit",
                "approve",
                "reject",
                "publish",
                "pause",
              ] as const
            ).map((value) => ({ value, label: labels[value] }))}
            onChange={(value) => {
              setAction(value);
              command.clear();
              setPreviewResult(undefined);
              setPreviewError(undefined);
            }}
          />
        </Form.Item>
        <HintNotice
          action={action}
          loading={access.loading}
          error={access.error}
          allowed={qualified}
          refresh={recheck}
        />
        {allowed && (
          <Button
            style={{ marginTop: 16 }}
            type="primary"
            htmlType="submit"
            disabled={command.busy || previewBusy}
          >
            {command.frozen ? "恢复原操作意图" : labels[action]}
          </Button>
        )}
      </Form>
      <Modal
        centered
        width={action === "preview" ? 760 : 600}
        className="campaign-modal"
        title={`${labels[action]} · ${form.getFieldValue("campaignId") ?? ""}`}
        open={open}
        onCancel={() => void close()}
        closable={!command.busy && !previewBusy}
        keyboard={!command.busy && !previewBusy}
        mask={{ closable: false }}
        footer={
          <Space wrap>
            <Button
              disabled={command.busy || previewBusy}
              onClick={() => void close()}
            >
              {command.frozen ? "返回页签（保留原意图）" : "关闭"}
            </Button>
            <Button
              disabled={command.busy || previewBusy || access.loading}
              onClick={recheck}
            >
              重新核验操作权限
            </Button>
            {allowed && (
              <Button
                type="primary"
                loading={command.busy || previewBusy}
                onClick={() =>
                  action === "preview"
                    ? previewForm.submit()
                    : void (async () => {
                        const value = form.getFieldsValue();
                        const receipt = await command.run(
                          path(),
                          { expectedVersion: value.expectedVersion },
                          action,
                        );
                        if (receipt) {
                          setOpen(false);
                          form.setFieldValue(
                            "expectedVersion",
                            receipt.lockVersion,
                          );
                          changed();
                          access.refresh();
                        }
                      })()
                }
              >
                {command.frozen
                  ? "原样重试操作"
                  : action === "preview"
                    ? "运行只读预览"
                    : `确认${labels[action]}`}
              </Button>
            )}
          </Space>
        }
      >
        <ErrorNotice error={command.error ?? previewError} />
        <Unknown frozen={command.frozen} />
        <HintNotice
          action={action}
          loading={access.loading}
          error={access.error}
          allowed={qualified}
          refresh={recheck}
          showRefresh={false}
        />
        <Descriptions
          column={1}
          items={[
            {
              key: "version",
              label: "内容版本",
              children: form.getFieldValue("version"),
            },
            ...(action === "preview"
              ? []
              : [
                  {
                    key: "lock",
                    label: "状态锁版本",
                    children: form.getFieldValue("expectedVersion"),
                  },
                ]),
          ]}
        />
        {action === "preview" ? (
          <div hidden={!allowed}>
            <Typography.Paragraph type="secondary">
              使用当前真实会员与已发布SKU事实；模拟时间不会回溯会员事实。
            </Typography.Paragraph>
            <Form
              form={previewForm}
              name="campaign-preview"
              layout="vertical"
              disabled={previewBusy}
              initialValues={{
                items: [{}],
                includePublishedCompetition: false,
              }}
              onValuesChange={() => dirty("preview", true)}
              onFinish={(value) => void preview(value)}
            >
              <Form.Item
                name="memberId"
                label="实际会员编号"
                rules={campaignIdRules}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item name="at" label="模拟时间（可选，浏览器时区）">
                <Input type="datetime-local" />
              </Form.Item>
              <Form.Item
                name="includePublishedCompetition"
                valuePropName="checked"
              >
                <Checkbox>包含当前已发布活动竞争</Checkbox>
              </Form.Item>
              <Form.List
                name="items"
                rules={[
                  {
                    validator: (_, items) =>
                      items?.length >= 1 && items.length <= 100
                        ? Promise.resolve()
                        : Promise.reject(new Error("请填写1至100项商品")),
                  },
                ]}
              >
                {(fields, { add, remove }, { errors }) => (
                  <>
                    {fields.map((field) => (
                      <div className="node-grid" key={field.key}>
                        <Form.Item
                          name={[field.name, "skuId"]}
                          label="实际SKU编号"
                          rules={campaignIdRules}
                        >
                          <Input maxLength={64} />
                        </Form.Item>
                        <Form.Item
                          name={[field.name, "quantity"]}
                          label="数量"
                          rules={[
                            {
                              required: true,
                              type: "integer",
                              min: 1,
                              max: 10000,
                            },
                          ]}
                        >
                          <InputNumber min={1} max={10000} precision={0} />
                        </Form.Item>
                        <Button
                          disabled={fields.length <= 1}
                          onClick={() => {
                            remove(field.name);
                            dirty("preview", true);
                          }}
                        >
                          移除商品
                        </Button>
                      </div>
                    ))}
                    <Form.ErrorList errors={errors} />
                    <Button
                      disabled={fields.length >= 100}
                      onClick={() => {
                        add({});
                        dirty("preview", true);
                      }}
                    >
                      添加商品
                    </Button>
                  </>
                )}
              </Form.List>
            </Form>
            {previewResult && <PreviewResult result={previewResult} />}
          </div>
        ) : (
          <Typography.Paragraph>
            确认对当前活动内容版本执行「{labels[action]}
            」。服务端将按状态锁版本检查原状态迁移；发布会暂停该活动其他已发布版本，暂停只影响新报价。
          </Typography.Paragraph>
        )}
      </Modal>
    </Space>
  );
}
function Workspace({
  context,
  onLogout,
  budget,
  children,
}: Props & {
  budget?: boolean;
  children: (client: typeof request, dirty: MarkDirty) => ReactNode;
}) {
  const [expired, setExpired] = useState(false),
    tasks = useRef(new Set<string>()),
    { modal } = App.useApp();
  const client = useMemo(
    () => campaignClient(context, () => setExpired(true)),
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
  const dirty: MarkDirty = (task, value) => {
    if (value) tasks.current.add(task);
    else tasks.current.delete(task);
  };
  const logout = () => {
    if (!tasks.current.size) void onLogout();
    else
      modal.confirm({
        title: "离开活动工作区？",
        content: "未保存输入或未确认结果将丢失。结果未知时请先原样重试确认。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  return (
    <main className="central-products">
      <Card>
        <PageHead
          eyebrow="企业经营"
          title={budget ? "活动预算" : "活动管理"}
          description={
            budget
              ? "独立查看实际内容版本预算余额。"
              : "创建活动草稿，预览实际资格并按授权审批发布。"
          }
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
            {children(client, dirty)}
          </RequestContext.Provider>
        )}
      </Card>
    </main>
  );
}
export function CentralCampaigns(props: Props) {
  const [tab, setTab] = useRouteState("workspaceTab", "directory"),
    [revision, setRevision] = useState(0),
    [selected, setSelected] = useState<CampaignView>();
  const blocked = useRef(false),
    { modal } = App.useApp();
  return (
    <Workspace {...props}>
      {(client, dirty) => (
        <Tabs
          activeKey={["create", "operations"].includes(tab) ? tab : "directory"}
          onChange={setTab}
          destroyOnHidden={false}
          items={[
            {
              key: "directory",
              label: "活动目录",
              forceRender: true,
              children: (
                <Directory
                  revision={revision}
                  select={(row) => {
                    if (blocked.current) {
                      modal.info({
                        title: "当前操作结果尚未确认",
                        content: "请先原样重试确认，完成后再切换目标版本。",
                      });
                      return;
                    }
                    setSelected(row);
                    setTab("operations");
                  }}
                />
              ),
            },
            {
              key: "create",
              label: "创建草稿",
              forceRender: true,
              children: (
                <Create
                  client={client}
                  dirty={dirty}
                  created={() => setRevision((n) => n + 1)}
                />
              ),
            },
            {
              key: "operations",
              label: "版本操作",
              forceRender: true,
              children: (
                <Operations
                  client={client}
                  dirty={dirty}
                  selected={selected}
                  changed={() => setRevision((n) => n + 1)}
                  targetBlocked={(value) => {
                    blocked.current = value;
                  }}
                />
              ),
            },
          ]}
        />
      )}
    </Workspace>
  );
}
function Budgets() {
  const [after, setAfter] = useRouteState("CampaignBudgets.after", "");
  const rows = useResource<CampaignBudget[]>(
    `/admin/campaign-budgets?after=${encodeURIComponent(after)}&limit=${CAMPAIGN_PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        按预算编号展示所有实际内容版本余额；不需要活动目录读取权限。金额为服务端当前快照。
      </Typography.Text>
      <Button onClick={rows.refresh}>刷新预算余额</Button>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table
          rowKey="budgetId"
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 920 }}
          columns={[
            { title: "预算编号", dataIndex: "budgetId", width: 300 },
            { title: "活动", dataIndex: "campaignId", width: 220 },
            { title: "内容版本", dataIndex: "version", width: 100 },
            {
              title: "上限（元）",
              dataIndex: "cap",
              width: 100,
              render: (value) => value ?? "未设上限",
            },
            { title: "已预占（元）", dataIndex: "held", width: 100 },
            { title: "已支出（元）", dataIndex: "spent", width: 100 },
          ]}
        />
      )}
      <Space wrap>
        <Button disabled={!after} onClick={() => setAfter("")}>
          回到首页
        </Button>
        <Button
          disabled={
            !!rows.error ||
            rows.loading ||
            rows.data?.length !== CAMPAIGN_PAGE_SIZE
          }
          onClick={() => setAfter(rows.data?.at(-1)?.budgetId ?? "")}
        >
          下一页
        </Button>
      </Space>
    </Space>
  );
}
export function CentralCampaignBudgets(props: Props) {
  return (
    <Workspace {...props} budget>
      {() => <Budgets />}
    </Workspace>
  );
}
