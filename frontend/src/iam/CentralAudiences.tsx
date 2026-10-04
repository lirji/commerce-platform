import { useListFilters } from "../shared/listFilters";
import { CursorBack } from "../shared/pagination";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
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
  type request,
  useResource,
} from "../shared/api";
import { useRouteState, useCursorState } from "../shared/routeState";
import { ErrorNotice, PageHead } from "../shared/ui";
import { HTTP, type Context } from "./api";
import {
  AUDIENCE_PAGE_SIZE,
  audienceClient,
  audienceIdentifier,
  type Audience,
  type AudienceView,
} from "./audienceClient";

type AudienceForm = Omit<Audience, "memberIds"> & { members?: string };
const membersOf = (text?: string) =>
  (text ?? "")
    .split(/\r?\n/)
    .map((id) => id.trim())
    .filter(Boolean);
const time = (value: string) => new Date(value).toLocaleString();
const forbidden = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;

function Directory({ revision }: { revision: number }) {
  const [after, setAfter] = useCursorState("AudienceDirectory.after", "");
  const filters = useListFilters(
    "AudienceDirectory.after",
    "/admin/audiences",
    () => setAfter(""),
  );
  const rows = useResource<AudienceView[]>(
    `/admin/audiences?after=${encodeURIComponent(after)}&${filters.query}`,
  );
  const refresh = rows.refresh;
  useEffect(() => {
    if (revision) refresh();
  }, [revision, refresh]);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        每个人群显示最新不可变版本摘要。目录不展示成员编号；零成员快照表示空集合。
      </Typography.Text>
      <Button onClick={refresh}>刷新人群目录</Button>
      {filters.toolbar}
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<AudienceView>
          rowKey="audienceId"
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 1100 }}
          columns={[
            {
              title: "人群",
              width: 240,
              render: (_, row) => (
                <Space orientation="vertical" size={0}>
                  <Typography.Text strong>{row.name}</Typography.Text>
                  <Typography.Text type="secondary">
                    {row.audienceId}
                  </Typography.Text>
                </Space>
              ),
            },
            { title: "版本", dataIndex: "version", width: 90 },
            { title: "成员数", dataIndex: "memberCount", width: 100 },
            { title: "来源", dataIndex: "source", width: 180 },
            {
              title: "数据时点",
              dataIndex: "watermark",
              render: time,
              width: 190,
            },
            {
              title: "有效截止",
              dataIndex: "validUntil",
              render: time,
              width: 190,
            },
            {
              title: "时间窗口",
              width: 120,
              render: (_, row) => {
                const now = Date.now();
                return new Date(row.watermark).getTime() > now ? (
                  <Tag>尚未到数据时点</Tag>
                ) : new Date(row.validUntil).getTime() <= now ? (
                  <Tag>已过期</Tag>
                ) : (
                  <Tag color="green">窗口内</Tag>
                );
              },
            },
          ]}
        />
      )}
      <Typography.Text type="secondary">
        时间按浏览器时区显示，窗口提示依据当前设备时间；实际业务使用仍由服务端核对新鲜度。
      </Typography.Text>
      <div className="pager-navigation">
        <CursorBack
          name={"AudienceDirectory.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={filters.limit}
        />
        <Button disabled={!after} onClick={() => setAfter("")}>
          回到首页
        </Button>
        <Button
          disabled={
            !!rows.error || rows.loading || rows.data?.length !== filters.limit
          }
          onClick={() => setAfter(rows.data?.at(-1)?.audienceId ?? "")}
        >
          下一页
        </Button>
      </div>
    </Space>
  );
}

function CreateAudience({
  client,
  markDirty,
  onCreated,
}: {
  client: typeof request;
  markDirty: (value: boolean) => void;
  onCreated: () => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    "/operations/audiences/create-access",
  );
  const [form] = Form.useForm<AudienceForm>();
  const intent = useRef<{ key: string; body: Audience } | null>(null),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<AudienceView>();
  const rechecking = useRef(false),
    observedLoading = useRef(false);
  useEffect(() => {
    if (!rechecking.current) return;
    if (access.loading) observedLoading.current = true;
    else if (observedLoading.current) {
      rechecking.current = false;
      if (access.data?.allowed && !access.error) setWriteDenied(false);
    }
  }, [access.loading, access.data, access.error]);
  const canWrite =
    !!access.data?.allowed &&
    !access.loading &&
    !access.error &&
    !writeDenied &&
    !(error instanceof ApiError && error.status >= 500);
  const recheck = () => {
    if (rechecking.current || access.loading) return;
    // 上一次成功提示不能在新的服务端复核完成前重新开放写入。
    rechecking.current = true;
    observedLoading.current = false;
    setWriteDenied(true);
    access.refresh();
    setError(undefined);
  };
  async function submit(value: AudienceForm) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    try {
      // 未知结果始终重用第一次转换后的时间、原成员序列和幂等键，不重新生成意图。
      if (!intent.current)
        intent.current = {
          key: crypto.randomUUID(),
          body: {
            audienceId: value.audienceId,
            version: value.version,
            name: value.name.trim(),
            source: value.source.trim(),
            watermark: new Date(value.watermark).toISOString(),
            validUntil: new Date(value.validUntil).toISOString(),
            memberIds: membersOf(value.members),
          },
        };
      setResult(
        await client<AudienceView>("/admin/audiences", {
          method: "POST",
          ...intent.current,
        }),
      );
      intent.current = null;
      setFrozen(false);
      markDirty(false);
      form.resetFields();
      onCreated();
      recheck();
    } catch (failure) {
      setError(failure as Error);
      if (failure instanceof ApiError && failure.status < 500 && !frozen)
        intent.current = null;
      else {
        setFrozen(true);
        markDirty(true);
      }
      if (forbidden(failure as Error)) {
        setWriteDenied(true);
        access.refresh();
      }
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      {result && (
        <Alert
          type="success"
          showIcon
          title="人群快照已创建"
          description={
            <Descriptions
              column={1}
              items={[
                { key: "id", label: "人群编号", children: result.audienceId },
                { key: "version", label: "版本", children: result.version },
                {
                  key: "count",
                  label: "实际成员数",
                  children: result.memberCount,
                },
                {
                  key: "watermark",
                  label: "数据时点",
                  children: time(result.watermark),
                },
                {
                  key: "until",
                  label: "有效截止",
                  children: time(result.validUntil),
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
          <Alert
            type="warning"
            title="该人群版本已存在，请核对编号和版本后纠正输入"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="切换页签保留本次意图。离开页面会丢失重试信息；权限撤销时请联系管理员核对已有结果。"
        />
      )}
      {!forbidden(access.error) && (
        <ErrorNotice error={access.error} onRetry={access.refresh} />
      )}
      <Card title="创建人群快照">
        {access.loading && <Spin description="正在核验创建权限" />}
        {!access.loading && !canWrite && (
          <Typography.Paragraph type="secondary">
            {forbidden(access.error) || writeDenied
              ? "当前未获人群创建权限；目录读取权限独立授予。"
              : "创建资格尚未确认，请重新核验权限。"}
          </Typography.Paragraph>
        )}
        {/* 权限暂不可用时隐藏而不卸载表单，保留未知结果的原意图及输入。 */}
        <Form
          hidden={!canWrite}
          name="audience-create"
          form={form}
          layout="vertical"
          style={{ maxWidth: 760 }}
          onValuesChange={() => markDirty(true)}
          onFinish={submit}
        >
          <Alert
            type="info"
            title="创建后版本不可修改；导入不会创建客户会员。"
            description="空成员列表创建空快照。时间按浏览器时区输入；可导入已过期快照，新鲜度窗口最多24小时。"
          />
          <Form.Item
            name="audienceId"
            label="人群编号"
            rules={[
              {
                required: true,
                pattern: audienceIdentifier,
                message: "请输入1至64位字母、数字、下划线、点、冒号或连字符",
              },
              {
                validator: (_, value) =>
                  value?.startsWith("dyn-")
                    ? Promise.reject(new Error("dyn-前缀保留给动态人群任务"))
                    : Promise.resolve(),
              },
            ]}
          >
            <Input maxLength={64} disabled={busy || frozen} />
          </Form.Item>
          <Form.Item
            name="version"
            label="快照版本"
            rules={[
              {
                required: true,
                type: "integer",
                min: 1,
                max: Number.MAX_SAFE_INTEGER,
                message: "请输入正安全整数版本",
              },
            ]}
          >
            <InputNumber
              min={1}
              max={Number.MAX_SAFE_INTEGER}
              precision={0}
              disabled={busy || frozen}
              style={{ width: "100%" }}
            />
          </Form.Item>
          {(["name", "source"] as const).map((name) => (
            <Form.Item
              key={name}
              name={name}
              label={name === "name" ? "人群名称" : "数据来源"}
              rules={[
                {
                  required: true,
                  whitespace: true,
                  max: 128,
                  message: "请输入不超过128字的内容",
                },
              ]}
            >
              <Input maxLength={128} disabled={busy || frozen} />
            </Form.Item>
          ))}
          <Form.Item
            name="watermark"
            label="数据时点"
            rules={[
              { required: true, message: "请选择数据时点" },
              {
                validator: (_, value) =>
                  value &&
                  (!Number.isFinite(new Date(value).getTime()) ||
                    new Date(value).getTime() > Date.now())
                    ? Promise.reject(new Error("数据时点不能晚于当前时间"))
                    : Promise.resolve(),
              },
            ]}
          >
            <Input type="datetime-local" disabled={busy || frozen} />
          </Form.Item>
          <Form.Item
            name="validUntil"
            label="有效截止时间"
            dependencies={["watermark"]}
            rules={[
              { required: true, message: "请选择有效截止时间" },
              {
                validator: (_, value) => {
                  const start = new Date(
                      form.getFieldValue("watermark"),
                    ).getTime(),
                    end = new Date(value).getTime();
                  return value &&
                    (!Number.isFinite(end) ||
                      (Number.isFinite(start) &&
                        (end <= start || end - start > 86400000)))
                    ? Promise.reject(
                        new Error("截止时间须晚于数据时点且窗口不超过24小时"),
                      )
                    : Promise.resolve();
                },
              },
            ]}
          >
            <Input type="datetime-local" disabled={busy || frozen} />
          </Form.Item>
          <Form.Item
            name="members"
            label="成员编号（每行一个，可留空）"
            rules={[
              {
                validator: (_, text) => {
                  const members = membersOf(text);
                  if (members.length > 500)
                    return Promise.reject(
                      new Error("每个快照最多500个成员编号"),
                    );
                  if (members.some((id) => !audienceIdentifier.test(id)))
                    return Promise.reject(
                      new Error("成员编号须为1至64位合法标识"),
                    );
                  if (new Set(members).size !== members.length)
                    return Promise.reject(
                      new Error("成员编号重复，请纠正输入"),
                    );
                  return Promise.resolve();
                },
              },
            ]}
          >
            <Input.TextArea
              rows={6}
              maxLength={40000}
              disabled={busy || frozen}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>
            {frozen ? "原样重试创建" : "确认创建人群"}
          </Button>
        </Form>
        <Button
          style={{ marginTop: 16 }}
          disabled={busy || access.loading}
          onClick={recheck}
        >
          重新核验创建权限
        </Button>
      </Card>
    </Space>
  );
}

/** 人群读取和创建各自核验；页签切换不销毁未保存或结果未知的意图。 */
export function CentralAudiences({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [tab, setTab] = useRouteState("workspaceTab", "directory");
  const [expired, setExpired] = useState(false),
    [revision, setRevision] = useState(0);
  const dirty = useRef(false),
    { modal } = App.useApp();
  const client = useMemo(
    () => audienceClient(context, () => setExpired(true)),
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
        title: "离开人群快照？",
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
          title="人群快照"
          description="查看人群最新版本，导入有界成员快照。"
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
              activeKey={tab === "create" ? "create" : "directory"}
              onChange={setTab}
              items={[
                {
                  key: "directory",
                  label: "人群目录",
                  children: <Directory revision={revision} />,
                },
                {
                  key: "create",
                  label: "创建人群",
                  children: (
                    <CreateAudience
                      client={client}
                      markDirty={(value) => {
                        dirty.current = value;
                      }}
                      onCreated={() => setRevision((n) => n + 1)}
                    />
                  ),
                },
              ]}
            />
          </RequestContext.Provider>
        )}
      </Card>
    </main>
  );
}
