import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
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
import { ErrorNotice, money, time } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const Action = { UPDATE: "update", REBUILD: "rebuild" } as const;
type Action = (typeof Action)[keyof typeof Action];
const Preference = { ENABLED: "ENABLED", DISABLED: "DISABLED" } as const;
const titles: Record<Action, string> = {
  update: "修改偏好",
  rebuild: "历史成交补建",
};
type Profile = {
  birthday: string | null;
  journeyEnabled: boolean;
  version: number;
};
type Progress = { next: string; scanned: number; done: boolean };
type Detail = {
  member: {
    memberId: string;
    displayName: string;
    memberLevel: string;
    status: string;
    version: number;
  };
  profile: Profile;
  facts: {
    browse30: number;
    cart30: number;
    completedOrders30: number;
    netSpend30: string;
    lastOrderAt: string | null;
    lastCartAt: string | null;
    daysSinceOrder: number | null;
    daysSinceJoin: number;
    birthdayToday: boolean;
  };
};
type Event = {
  sequenceId: number;
  eventId: string;
  kind: string;
  storeId: string;
  skuId: string;
  occurredAt: string;
};
type Values = {
  memberId?: string;
  expectedVersion?: number;
  birthday?: string;
  preference?: (typeof Preference)[keyof typeof Preference];
  reason?: string;
  after?: string;
  limit?: number;
};
const PAGE_SIZE = 50,
  identifier = /^[A-Za-z0-9_-]{1,100}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;
const statuses: Record<string, string> = {
  ACTIVE: "正常",
  FROZEN: "冻结",
  CLOSED: "已注销",
};
const events: Record<string, string> = {
  BROWSE: "查看商品",
  ADD_TO_CART: "加入购物袋",
};

/** 凭据仅进入行为页允许的路径，不能借中央会话访问旧通用控制台。 */
function behaviorClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/member-behavior\/[A-Za-z0-9_-]{1,100}(\/events\?.*)?$/.test(
          path,
        ) ||
          /^\/operations\/member-behavior\/(update|rebuild)-access$/.test(
            path,
          ))) ||
      (method === "POST" &&
        /^\/admin\/member-behavior\/(rebuild|[A-Za-z0-9_-]{1,100}\/profile)$/.test(
          path,
        ))
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

/** 三个能力工作区互相独立，未知写入意图在切页签时保留。 */
export function CentralBehavior({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => behaviorClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty.current.size) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const logout = () => {
    if (!dirty.current.size) void onLogout();
    else
      modal.confirm({
        title: "离开会员行为管理？",
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
          <Space wrap>
            <Typography.Title level={2} style={{ margin: 0 }}>
              会员行为管理
            </Typography.Title>
            <Button onClick={logout}>退出登录</Button>
          </Space>
          <Typography.Text type="secondary">
            当前组织：{context.tenant}
          </Typography.Text>
          {expired ? (
            <Alert
              type="warning"
              title="登录已失效，请重新登录"
              action={<Button onClick={() => void onLogout()}>返回登录</Button>}
            />
          ) : (
            <RequestContext.Provider value={client}>
              <Tabs
                items={[
                  {
                    key: "read",
                    label: "行为查询",
                    children: <BehaviorSearch />,
                  },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <BehaviorCommand
                        action={action}
                        client={client}
                        markDirty={(value) => {
                          if (value) dirty.current.add(action);
                          else dirty.current.delete(action);
                        }}
                      />
                    ),
                  })),
                ]}
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}

function BehaviorSearch() {
  const [member, setMember] = useState<string>(),
    [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        使用已知会员编号查询行为资料；修改偏好与补建分别授权。
      </Typography.Text>
      <Form
        name="behavior-search"
        layout="vertical"
        style={{ width: "100%", maxWidth: 700 }}
        onFinish={(v) => {
          setMember(v.memberId);
          setRevision((n) => n + 1);
        }}
      >
        <Form.Item
          name="memberId"
          label="查询会员编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入1至100位字母、数字、下划线或连字符",
            },
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Button type="primary" htmlType="submit">
          查询会员行为
        </Button>
      </Form>
      {member ? (
        <BehaviorDetail key={`${member}:${revision}`} member={member} />
      ) : (
        <Alert type="info" title="输入会员编号后查看行为资料与交互记录" />
      )}
    </Space>
  );
}

function BehaviorDetail({ member }: { member: string }) {
  const [after, setAfter] = useState(0);
  const detail = useResource<Detail>(
    `/admin/member-behavior/${encodeURIComponent(member)}`,
  );
  const rows = useResource<Event[]>(
    `/admin/member-behavior/${encodeURIComponent(member)}/events?after=${after}&limit=${PAGE_SIZE}`,
  );
  const value = detail.data;
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Button
        onClick={() => {
          detail.refresh();
          rows.refresh();
        }}
      >
        刷新行为资料
      </Button>
      <ErrorNotice error={detail.error} />
      {!detail.error &&
        (value ? (
          <Card title={`${value.member.displayName} · 行为资料`}>
            <Descriptions
              column={{ xs: 1, sm: 2, lg: 3 }}
              items={[
                {
                  key: "id",
                  label: "会员编号",
                  children: value.member.memberId,
                },
                {
                  key: "status",
                  label: "会员状态",
                  children:
                    statuses[value.member.status] ?? value.member.status,
                },
                {
                  key: "level",
                  label: "会员等级",
                  children: value.member.memberLevel,
                },
                {
                  key: "version",
                  label: "偏好版本",
                  children: value.profile.version,
                },
                {
                  key: "birthday",
                  label: "生日月日",
                  children: value.profile.birthday ?? "未填写",
                },
                {
                  key: "journey",
                  label: "站内营销旅程",
                  children: value.profile.journeyEnabled ? "接收" : "已关闭",
                },
                {
                  key: "browse",
                  label: "近30天浏览",
                  children: value.facts.browse30,
                },
                {
                  key: "cart",
                  label: "近30天加购",
                  children: value.facts.cart30,
                },
                {
                  key: "orders",
                  label: "近30天完成订单",
                  children: value.facts.completedOrders30,
                },
                {
                  key: "spend",
                  label: "近30天净现金消费",
                  children: money(value.facts.netSpend30),
                },
                {
                  key: "orderAt",
                  label: "最近完成订单下单时间",
                  children: time(value.facts.lastOrderAt),
                },
                {
                  key: "cartAt",
                  label: "近30天最近加购",
                  children: time(value.facts.lastCartAt),
                },
                {
                  key: "days",
                  label: "距最近成交",
                  children:
                    value.facts.daysSinceOrder == null
                      ? "暂无成交事实"
                      : `${value.facts.daysSinceOrder} 天`,
                },
              ]}
            />
            <Typography.Text type="secondary">
              统计窗口包含UTC今天及前29天；完成订单按原下单时间统计，退款冲减净消费。交互信号不作为成交事实。
            </Typography.Text>
          </Card>
        ) : (
          <Typography.Text>正在读取行为资料</Typography.Text>
        ))}
      <Card title="商品交互记录">
        <Space orientation="vertical" style={{ width: "100%" }}>
          <ErrorNotice error={rows.error} />
          {!rows.error && (
            <Table<Event>
              rowKey="sequenceId"
              dataSource={rows.data ?? []}
              loading={rows.loading}
              pagination={false}
              scroll={{ x: 800 }}
              columns={[
                { title: "时间", dataIndex: "occurredAt", render: time },
                {
                  title: "行为",
                  dataIndex: "kind",
                  render: (v) => events[v] ?? v,
                },
                { title: "事件编号", dataIndex: "eventId" },
                { title: "门店", dataIndex: "storeId" },
                { title: "商品", dataIndex: "skuId" },
              ]}
            />
          )}
          <Space>
            <Button
              disabled={!after || rows.loading || !!rows.error}
              onClick={() => setAfter(0)}
            >
              最早交互
            </Button>
            <Button
              disabled={
                rows.loading ||
                !!rows.error ||
                rows.data?.length !== PAGE_SIZE ||
                !Number.isSafeInteger(rows.data?.at(-1)?.sequenceId)
              }
              onClick={() => setAfter(rows.data!.at(-1)!.sequenceId)}
            >
              下一批交互
            </Button>
          </Space>
        </Space>
      </Card>
    </Space>
  );
}

/** 生日只取月日，使用闰年检查02-29，避免时区和出生年份引入额外业务含义。 */
function validBirthday(raw?: string) {
  const value = raw?.trim();
  if (!value) return true;
  if (!/^\d{2}-\d{2}$/.test(value)) return false;
  const [month, day] = value.split("-").map(Number),
    date = new Date(Date.UTC(2020, month - 1, day));
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

/** 独立写权限不以读取为前提；未知结果保留原目标、请求和幂等键。 */
function BehaviorCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (v: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/member-behavior/${action}-access`,
  );
  const [form] = Form.useForm<Values>();
  const intent = useRef<{
      path: string;
      body: unknown;
      key: string;
      member?: string;
    } | null>(null),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<{
      value: Profile | Progress;
      member?: string;
    }>();
  const canWrite =
    !!access.data?.allowed && !access.error && !access.loading && !writeDenied;
  const refresh = () => {
    access.refresh();
    setWriteDenied(false);
  };
  async function submit(value: Values) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        member: action === Action.UPDATE ? value.memberId : undefined,
        path:
          action === Action.UPDATE
            ? `/admin/member-behavior/${encodeURIComponent(value.memberId!)}/profile`
            : "/admin/member-behavior/rebuild",
        body:
          action === Action.UPDATE
            ? {
                expectedVersion: value.expectedVersion,
                birthday: value.birthday?.trim() || null,
                journeyEnabled: value.preference === Preference.ENABLED,
                reason: value.reason!.trim(),
              }
            : { after: value.after?.trim() || "", limit: value.limit },
      };
    try {
      const { path, body, key, member } = intent.current;
      const saved = await client<Profile | Progress>(path, {
        method: "POST",
        body,
        key,
      });
      setResult({ value: saved, member });
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
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ width: "100%", maxWidth: 700 }}
    >
      <Space wrap>
        <Typography.Text>{titles[action]}与读取相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title={`${titles[action]}成功`}
          description={
            "version" in result.value
              ? `会员：${result.member}；偏好版本：${result.value.version}；生日：${result.value.birthday ?? "未填写"}；站内营销旅程：${result.value.journeyEnabled ? "接收" : "已关闭"}。行为资料需重新查询。`
              : `本批扫描 ${result.value.scanned} 单；${result.value.done ? "已到当前订单末尾" : "可继续下一批"}；下一批游标：${result.value.next || "起点"}。扫描数不等于新增成交数。`
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对会员状态与最新偏好版本，再提交操作"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息。若权限已撤销，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && <ErrorNotice error={access.error} />}
      <Card title={titles[action]} loading={access.loading}>
        {canWrite ? (
          <Form
            name={`behavior-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            {action === Action.UPDATE ? (
              <>
                <Form.Item
                  name="memberId"
                  label="会员编号"
                  rules={[
                    {
                      required: true,
                      pattern: identifier,
                      message: "请输入1至100位字母、数字、下划线或连字符",
                    },
                  ]}
                >
                  <Input disabled={busy || frozen} maxLength={100} />
                </Form.Item>
                <Form.Item
                  name="expectedVersion"
                  label="当前偏好版本"
                  extra="首次偏好填0；此版本与会员档案版本独立，非正常会员不能修改。"
                  rules={[
                    {
                      required: true,
                      type: "integer",
                      min: 0,
                      max: Number.MAX_SAFE_INTEGER,
                      message: "请输入非负安全整数",
                    },
                  ]}
                >
                  <InputNumber
                    min={0}
                    max={Number.MAX_SAFE_INTEGER}
                    precision={0}
                    disabled={busy || frozen}
                    style={{ width: "100%" }}
                  />
                </Form.Item>
                <Form.Item
                  name="birthday"
                  label="生日月日"
                  extra="填写MM-DD，支持02-29；留空清除，按UTC经营日判断。"
                  rules={[
                    {
                      validator: (_, value) =>
                        validBirthday(value)
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error(
                                "请输入有效月日，如02-29；也可留空清除",
                              ),
                            ),
                    },
                  ]}
                >
                  <Input
                    maxLength={5}
                    disabled={busy || frozen}
                    placeholder="MM-DD"
                  />
                </Form.Item>
                <Form.Item
                  name="preference"
                  label="站内营销旅程"
                  rules={[{ required: true, message: "请选择接收或关闭" }]}
                >
                  <Select
                    disabled={busy || frozen}
                    options={[
                      { value: Preference.ENABLED, label: "接收旅程" },
                      { value: Preference.DISABLED, label: "关闭旅程" },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="reason"
                  label="变更原因"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      max: 256,
                      message: "请输入不超过256字的变更原因",
                    },
                  ]}
                >
                  <Input.TextArea
                    maxLength={256}
                    rows={3}
                    disabled={busy || frozen}
                  />
                </Form.Item>
              </>
            ) : (
              <>
                <Typography.Paragraph type="secondary">
                  每次只补建一个有限批次；使用返回游标手动继续，不会自动启动后台全量重建。
                </Typography.Paragraph>
                <Form.Item
                  name="after"
                  label="补建游标"
                  extra="首次留空；后续填写上次返回的下一批游标。"
                  rules={[
                    {
                      pattern: /^[A-Za-z0-9_-]{0,100}$/,
                      message: "留空或填写不超过100位的有效标识",
                    },
                  ]}
                >
                  <Input maxLength={100} disabled={busy || frozen} />
                </Form.Item>
                <Form.Item
                  name="limit"
                  label="本批最多扫描订单数"
                  rules={[
                    {
                      required: true,
                      type: "integer",
                      min: 1,
                      max: PAGE_SIZE,
                      message: "请输入1至50的整数",
                    },
                  ]}
                >
                  <InputNumber
                    min={1}
                    max={PAGE_SIZE}
                    precision={0}
                    disabled={busy || frozen}
                    style={{ width: "100%" }}
                  />
                </Form.Item>
              </>
            )}
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? "原样重试" : `确认${titles[action]}`}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(access.error) || writeDenied
              ? `当前没有${titles[action]}权限，可联系管理员申请`
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}
