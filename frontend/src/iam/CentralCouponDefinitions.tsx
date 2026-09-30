import { useRouteState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Radio,
  Space,
  Table,
  Tabs,
  Typography,
} from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ApiError,
  RequestContext,
  type request,
  useResource,
} from "../shared/api";
import { ErrorNotice, PageHead } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const Issuance = { PUBLIC: "PUBLIC", SOURCE_ONLY: "SOURCE_ONLY" } as const;
type Definition = {
  definitionId: string;
  version: number;
  storeId: string;
  name: string;
  minimumSpend: string;
  discountAmount: string;
  validFrom: string;
  validTo: string;
  quota: number;
  stackable: boolean;
  platformFundingBps: number | null;
  issuanceMode: (typeof Issuance)[keyof typeof Issuance];
  validityDays: number | null;
};
type DefinitionView = { content: Definition; issued: number };
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const money = /^\d{1,12}(\.\d{1,2})?$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 目录键和解码后的标识逐一核对；中央凭据不能发送到任意URL或旧控制台客户端。 */
function couponClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    let directory = false;
    if (path.startsWith("/admin/coupon-definitions?")) {
      const query = new URLSearchParams(path.slice(path.indexOf("?") + 1));
      directory =
        Array.from(query.keys()).length === 3 &&
        query.has("after") &&
        query.get("limit") === String(PAGE_SIZE) &&
        identifier.test(query.get("storeId") ?? "") &&
        (query.get("after") === "" ||
          identifier.test(query.get("after") ?? ""));
    }
    if (!(
      (method === "GET" &&
        (directory ||
          path === "/operations/coupon-definitions/create-access")) ||
      (method === "POST" && path === "/admin/coupon-definitions")
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (options.key) headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    }).catch(() => {
      throw new Error("服务连接中断，请稍后重试");
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    return (await response.json()) as T;
  };
}

function Directory() {
  const [store, setStore] = useState<string>(),
    [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        使用已知门店编号查询每个券定义的最新版本；目录权限不包含门店读取或创建权限，已发数量不代表当前可领取数量。
      </Typography.Text>
      <Form
        name="coupon-definition-search"
        layout="vertical"
        style={{ maxWidth: 700, width: "100%" }}
        onFinish={(v) => {
          setStore(v.storeId);
          setRevision((n) => n + 1);
        }}
      >
        <Form.Item
          name="storeId"
          label="查询门店编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入1至64位合法标识",
            },
          ]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Button type="primary" htmlType="submit">
          查询券定义
        </Button>
      </Form>
      {store && <DefinitionList key={`${store}:${revision}`} store={store} />}
    </Space>
  );
}
function DefinitionList({ store }: { store: string }) {
  const [after, setAfter] = useRouteState("DefinitionList.after", "");
  const rows = useResource<DefinitionView[]>(
    `/admin/coupon-definitions?storeId=${encodeURIComponent(store)}&after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<DefinitionView>
          rowKey={(v) => v.content.definitionId}
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 2000 }}
          columns={[
            { title: "券定义编号", dataIndex: ["content", "definitionId"] },
            { title: "版本", dataIndex: ["content", "version"] },
            { title: "名称", dataIndex: ["content", "name"] },
            { title: "门店", dataIndex: ["content", "storeId"] },
            { title: "最低消费（元）", dataIndex: ["content", "minimumSpend"] },
            {
              title: "优惠金额（元）",
              dataIndex: ["content", "discountAmount"],
            },
            { title: "发行额度", dataIndex: ["content", "quota"] },
            { title: "已发数量", dataIndex: "issued" },
            {
              title: "允许叠加",
              dataIndex: ["content", "stackable"],
              render: (v) => (v ? "是" : "否"),
            },
            {
              title: "平台出资比例",
              dataIndex: ["content", "platformFundingBps"],
              render: (v) => `${(v ?? 0) / 100}%`,
            },
            {
              title: "发行方式",
              dataIndex: ["content", "issuanceMode"],
              render: (v) =>
                v === Issuance.PUBLIC
                  ? "公开领取"
                  : v === Issuance.SOURCE_ONLY
                    ? "受控发放"
                    : v,
            },
            {
              title: "领取后有效天数",
              dataIndex: ["content", "validityDays"],
              render: (v) => (v ? `${v}天` : "固定时间"),
            },
            {
              title: "开始时间",
              dataIndex: ["content", "validFrom"],
              render: (v) => new Date(v).toLocaleString(),
            },
            {
              title: "截止时间",
              dataIndex: ["content", "validTo"],
              render: (v) => new Date(v).toLocaleString(),
            },
          ]}
        />
      )}
      <Space>
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          目录首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
          }
          onClick={() => setAfter(rows.data!.at(-1)!.content.definitionId)}
        >
          下一批定义
        </Button>
      </Space>
    </Space>
  );
}

/** 原始请求在结果未知时保持不变，重试不能生成第二次创建意图。 */
function CreateDefinition({
  client,
  markDirty,
}: {
  client: typeof request;
  markDirty: (v: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    "/operations/coupon-definitions/create-access",
  );
  const [form] = Form.useForm<Definition>();
  const intent = useRef<{ path: string; key: string; body: Definition } | null>(
      null,
    ),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<DefinitionView>();
  const canWrite =
    !!access.data?.allowed &&
    !access.error &&
    !access.loading &&
    !writeDenied &&
    !(error instanceof ApiError && error.status >= 500);
  const refresh = () => {
    access.refresh();
    setWriteDenied(false);
    setError(undefined);
  };
  async function submit(value: Definition) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    try {
      if (!intent.current)
        intent.current = {
          path: "/admin/coupon-definitions",
          key: crypto.randomUUID(),
          body: {
            definitionId: value.definitionId,
            version: value.version,
            storeId: value.storeId,
            name: value.name.trim(),
            minimumSpend: value.minimumSpend,
            discountAmount: value.discountAmount,
            validFrom: new Date(value.validFrom).toISOString(),
            validTo: new Date(value.validTo).toISOString(),
            quota: value.quota,
            stackable: value.stackable,
            issuanceMode: value.issuanceMode,
            platformFundingBps: value.platformFundingBps ?? null,
            validityDays: value.validityDays ?? null,
          },
        };
      const { path, ...options } = intent.current;
      setResult(
        await client<DefinitionView>(path, { method: "POST", ...options }),
      );
      intent.current = null;
      setFrozen(false);
      markDirty(false);
      form.resetFields();
      refresh();
    } catch (failure) {
      setError(failure as Error);
      if (failure instanceof ApiError && failure.status < 500 && !frozen)
        intent.current = null;
      else {
        setFrozen(true);
        markDirty(true);
      }
      if (denied(failure as Error)) {
        setWriteDenied(true);
        access.refresh();
      }
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  const idField = (name: "definitionId" | "storeId", label: string) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        {
          required: true,
          pattern: identifier,
          message: "请输入1至64位字母、数字、下划线、点、冒号或连字符",
        },
      ]}
    >
      <Input maxLength={64} disabled={busy || frozen} />
    </Form.Item>
  );
  const integer = (
    name: "version" | "quota" | "platformFundingBps" | "validityDays",
    label: string,
    min: number,
    max: number,
    required = true,
  ) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        {
          required,
          type: "integer",
          min,
          max,
          message: `请输入${min}至${max}的整数`,
        },
      ]}
    >
      <InputNumber
        min={min}
        max={max}
        precision={0}
        disabled={busy || frozen}
        style={{ width: "100%" }}
      />
    </Form.Item>
  );
  const amount = (name: "minimumSpend" | "discountAmount", label: string) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        {
          required: true,
          pattern: money,
          message: "请输入不超过999999999999.99元、最多两位小数的金额",
        },
        {
          validator: (_, value) =>
            name === "discountAmount" &&
            typeof value === "string" &&
            /^0+(\.0+)?$/.test(value)
              ? Promise.reject(new Error("优惠金额必须大于零"))
              : Promise.resolve(),
        },
      ]}
    >
      <Input inputMode="decimal" maxLength={15} disabled={busy || frozen} />
    </Form.Item>
  );
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ maxWidth: 700, width: "100%" }}
    >
      <Space wrap>
        <Typography.Text>创建与目录读取权限相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title="创建券定义成功"
          description={
            <Descriptions
              column={{ xs: 1, sm: 2 }}
              items={[
                {
                  key: "id",
                  label: "券定义编号",
                  children: result.content.definitionId,
                },
                {
                  key: "version",
                  label: "版本",
                  children: String(result.content.version),
                },
                {
                  key: "issued",
                  label: "已发数量",
                  children: String(result.issued),
                },
                {
                  key: "mode",
                  label: "发行方式",
                  children:
                    result.content.issuanceMode === Issuance.PUBLIC
                      ? "公开领取"
                      : "受控发放",
                },
              ]}
            />
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert type="warning" title="请核对券定义编号和版本后，再提交操作" />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息；权限已撤销时，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && <ErrorNotice error={access.error} />}
      <Card title="创建券定义" loading={access.loading}>
        {canWrite ? (
          <Form
            name="coupon-definition-create"
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            <Alert
              type="info"
              title="定义版本创建后不可修改；创建不会自动给客户发券。"
            />
            {idField("definitionId", "券定义编号")}
            {integer("version", "定义版本", 1, Number.MAX_SAFE_INTEGER)}
            {idField("storeId", "门店编号")}
            <Form.Item
              name="name"
              label="券名称"
              rules={[
                {
                  required: true,
                  whitespace: true,
                  max: 128,
                  message: "请输入不超过128字的名称",
                },
              ]}
            >
              <Input maxLength={128} disabled={busy || frozen} />
            </Form.Item>
            {amount("minimumSpend", "最低消费金额（元）")}
            {amount("discountAmount", "优惠金额（元）")}
            {integer("quota", "发行额度", 1, 1000000)}
            <Form.Item
              name="stackable"
              label="允许与单项活动叠加"
              rules={[{ required: true, message: "请选择是否允许叠加" }]}
            >
              <Radio.Group
                disabled={busy || frozen}
                options={[
                  { value: true, label: "允许" },
                  { value: false, label: "不允许" },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="issuanceMode"
              label="发行方式"
              rules={[{ required: true, message: "请选择发行方式" }]}
            >
              <Radio.Group
                disabled={busy || frozen}
                options={[
                  { value: Issuance.PUBLIC, label: "公开领取" },
                  { value: Issuance.SOURCE_ONLY, label: "受控发放" },
                ]}
              />
            </Form.Item>
            {integer(
              "platformFundingBps",
              "平台出资基点（可留空）",
              0,
              10000,
              false,
            )}
            <Typography.Paragraph type="secondary">
              10000基点为100%；留空按现有规则平台承担0%，其余由商家承担。
            </Typography.Paragraph>
            {integer("validityDays", "领取后有效天数（可留空）", 0, 366, false)}
            <Typography.Paragraph type="secondary">
              留空或0使用固定起止时间；正数从实际领取时刻起算有效期。时间按当前浏览器时区填写。
            </Typography.Paragraph>
            <Form.Item
              name="validFrom"
              label="开始时间"
              rules={[{ required: true, message: "请选择开始时间" }]}
            >
              <Input type="datetime-local" disabled={busy || frozen} />
            </Form.Item>
            <Form.Item
              name="validTo"
              label="截止时间"
              dependencies={["validFrom"]}
              rules={[
                { required: true, message: "请选择截止时间" },
                {
                  validator: (_, value) =>
                    value &&
                    form.getFieldValue("validFrom") &&
                    new Date(value).getTime() <=
                      new Date(form.getFieldValue("validFrom")).getTime()
                      ? Promise.reject(new Error("截止时间必须晚于开始时间"))
                      : Promise.resolve(),
                },
              ]}
            >
              <Input type="datetime-local" disabled={busy || frozen} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? "原样重试" : "确认创建券定义"}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(access.error) || writeDenied
              ? "当前没有创建券定义权限，可联系管理员申请"
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}

/** 独立创建资格不依赖目录读取；离开前提示未确认的创建意图。 */
export function CentralCouponDefinitions({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    "directory",
  );
  const [expired, setExpired] = useState(false),
    dirty = useRef(false);
  const { modal } = App.useApp();
  const client = useMemo(
    () => couponClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const logout = () => {
    if (!dirty.current) void onLogout();
    else
      modal.confirm({
        title: "离开券定义？",
        content:
          "未保存输入或未确认结果将离开当前页面。结果未知时请先原样重试确认，避免重复操作。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  return (
    <main className="central-products">
      <Card>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <PageHead
            eyebrow="企业经营"
            title="优惠券定义"
            description="维护券定义、适用范围与发布版本。"
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
              <Tabs
                activeKey={workspaceTab}
                onChange={setWorkspaceTab}
                items={[
                  {
                    key: "directory",
                    label: "券定义目录",
                    children: <Directory />,
                  },
                  {
                    key: "create",
                    label: "创建券定义",
                    children: (
                      <CreateDefinition
                        client={client}
                        markDirty={(v) => {
                          dirty.current = v;
                        }}
                      />
                    ),
                  },
                ]}
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}
