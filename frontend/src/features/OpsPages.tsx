import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tabs,
} from "antd";
import { useState } from "react";
import type { Governed, PageDefinition, PageRender } from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
import {
  ActionButton,
  CommandModal,
  ErrorNotice,
  Fields,
  PageHead,
  Status,
  Detail,
  type Values,
} from "../shared/ui";
import { CampaignEditor, Governance } from "../shared/marketing";
import { couponBody, couponFields } from "../shared/formSchemas";
const sources = [
  { value: "CAMPAIGNS", label: "租户活动（前20项）" },
  { value: "JOURNEYS", label: "租户旅程（前20项）" },
  { value: "BUDGETS", label: "租户预算（前20项）" },
  { value: "COUPONS", label: "当前店铺券定义（前20项）" },
  { value: "ENTITLEMENTS", label: "当前店铺权益定义（前20项）" },
];
const actions = [
  { value: "CREATE_CAMPAIGN", label: "创建活动" },
  { value: "CREATE_COUPON", label: "创建优惠券" },
  { value: "ENROLL_JOURNEY", label: "手工入组旅程" },
];
function RenderPage({
  render,
  refresh,
}: {
  render: PageRender;
  refresh: () => void;
}) {
  const d = render.page.content;
  return (
    <div className={render.preview ? "readonly-preview" : ""}>
      <h2>{d.title}</h2>
      {render.preview && (
        <Alert
          type="info"
          showIcon
          title="只读预览：数据来自真实接口，操作按钮已禁用。"
        />
      )}
      <Space wrap className="section-actions">
        {d.actions.map((a) => {
          const path =
            "/admin/ops-pages/" +
            encode(d.pageId) +
            "/" +
            d.version +
            "/actions/" +
            encode(a.id);
          if (render.preview)
            return (
              <Button key={a.id} disabled>
                {a.label}
              </Button>
            );
          return a.kind === "CREATE_CAMPAIGN" ? (
            <CampaignEditor
              key={a.id}
              store={d.storeId}
              onDone={refresh}
              label={a.label}
              path={path}
              wrap
            />
          ) : a.kind === "CREATE_COUPON" ? (
            <CommandModal
              key={a.id}
              title={a.label}
              path={path}
              fields={couponFields}
              build={(v) => ({ coupon: couponBody(v, d.storeId) })}
              onDone={refresh}
            />
          ) : (
            <CommandModal
              key={a.id}
              title={a.label}
              path={path}
              fields={[
                { name: "journeyId", label: "旅程标识" },
                {
                  name: "version",
                  label: "内容版本",
                  type: "number",
                  min: 1,
                  initial: 1,
                },
                { name: "memberId", label: "会员标识" },
                { name: "eventKey", label: "触发去重标识" },
              ]}
              build={(v) => ({ enrollment: v })}
              onDone={refresh}
            />
          );
        })}
      </Space>
      {d.sections.map((s) => {
        const data = render.data.find((r) => r.id === s.id)?.rows ?? [];
        const rows: Record<string, unknown>[] = data.map((item, index) => {
          const r = item as Record<string, unknown>;
          return {
            ...((r.content ?? {}) as Record<string, unknown>),
            ...r,
            _key: index,
          };
        });
        const names: Record<string, string> = {
          name: "名称",
          title: "标题",
          campaignId: "活动",
          journeyId: "旅程",
          definitionId: "券定义",
          benefitId: "权益",
          version: "版本",
          status: "状态",
          quota: "额度",
          issued: "已发行",
          held: "预占",
          spent: "消耗",
          cap: "预算上限",
          discountAmount: "优惠金额",
        };
        const keys = Object.keys(names)
          .filter((k) => rows.some((r) => r[k] !== undefined))
          .slice(0, 7);
        return (
          <Card className="ops-section" title={s.title} key={s.id}>
            <Table<Record<string, unknown>>
              rowKey="_key"
              dataSource={rows}
              pagination={false}
              scroll={{ x: 600 }}
              columns={keys.map((key) => ({
                title: names[key],
                dataIndex: key,
                render: (value: unknown) =>
                  key === "status" ? (
                    <Status value={String(value)} />
                  ) : value == null ? (
                    "—"
                  ) : (
                    String(value)
                  ),
              }))}
            />
            <p className="muted">
              展示前20项。完整列表与更多操作请进入对应业务页面。
            </p>
          </Card>
        );
      })}
    </div>
  );
}
function PublishedPage({ id }: { id: string }) {
  const r = useResource<PageRender>(
    "/admin/ops-pages/" + encode(id) + "/render",
  );
  return (
    <>
      <Button onClick={r.refresh}>刷新页面</Button>
      <ErrorNotice error={r.error} />
      {r.data && <RenderPage render={r.data} refresh={r.refresh} />}
    </>
  );
}
function PageVersions({ id, onDone }: { id: string; onDone: () => void }) {
  const r = useResource<Governed<PageDefinition>[]>(
    "/admin/ops-pages/" + encode(id) + "/versions",
  );
  return (
    <>
      <ErrorNotice error={r.error} />
      <Table<Governed<PageDefinition>>
        rowKey={(v) => v.content.version}
        dataSource={r.data}
        pagination={false}
        columns={[
          { title: "版本", render: (_, v) => v.content.version },
          {
            title: "状态",
            dataIndex: "status",
            render: (v: string) => <Status value={v} />,
          },
          {
            title: "操作",
            render: (_, v) => (
              <Space>
                <Governance
                  base="/admin/ops-pages"
                  id={id}
                  version={v.content.version}
                  status={v.status}
                  lockVersion={v.lockVersion}
                  onDone={() => {
                    r.refresh();
                    onDone();
                  }}
                />
                {v.status === "PAUSED" && (
                  <ActionButton
                    label="回退到此版本"
                    path={
                      "/admin/ops-pages/" +
                      encode(id) +
                      "/" +
                      v.content.version +
                      "/rollback"
                    }
                    body={{ expectedVersion: v.lockVersion }}
                    onDone={() => {
                      r.refresh();
                      onDone();
                    }}
                  />
                )}
              </Space>
            ),
          },
        ]}
        expandable={{ expandedRowRender: (v) => <Detail value={v.content} /> }}
      />
    </>
  );
}
export function OpsPages({ store }: { store: string }) {
  const r = useResource<Governed<PageDefinition>[]>("/admin/ops-pages");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<PageDefinition>();
  const [selected, setSelected] = useState<string>();
  const [preview, setPreview] = useState<PageRender>();
  const [form] = Form.useForm();
  const command = useCommand();
  const body = (v: Values) => ({ ...v, storeId: store }) as PageDefinition;
  return (
    <>
      <PageHead
        eyebrow="平台工具"
        title="低代码运营页面"
        description="组合可信数据源与业务动作，预览、审批后发布。"
        extra={
          <>
            <Button onClick={r.refresh}>刷新</Button>
            <Button
              type="primary"
              disabled={!store}
              onClick={() => {
                setDraft(undefined);
                setPreview(undefined);
                setEditing(true);
              }}
            >
              创建运营页面
            </Button>
          </>
        }
      />
      <ErrorNotice error={r.error} />
      <Card>
        <Table<Governed<PageDefinition>>
          rowKey={(row) => row.content.pageId}
          dataSource={r.data}
          pagination={false}
          loading={r.loading}
          columns={[
            { title: "页面名称", render: (_, row) => row.content.title },
            { title: "版本", render: (_, row) => row.content.version },
            {
              title: "组件数",
              render: (_, row) => row.content.sections.length,
            },
            {
              title: "状态",
              dataIndex: "status",
              render: (v: string) => <Status value={v} />,
            },
            {
              title: "操作",
              render: (_, row) => (
                <Space wrap>
                  <Button
                    size="small"
                    onClick={() => setSelected(row.content.pageId)}
                  >
                    打开 / 版本
                  </Button>
                  <Button
                    size="small"
                    onClick={() => {
                      setDraft({
                        ...row.content,
                        version: row.content.version + 1,
                      });
                      setPreview(undefined);
                      setEditing(true);
                    }}
                  >
                    创建新版本
                  </Button>
                  <Governance
                    base="/admin/ops-pages"
                    id={row.content.pageId}
                    version={row.content.version}
                    status={row.status}
                    lockVersion={row.lockVersion}
                    onDone={r.refresh}
                  />
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <Drawer
        title="运营页面"
        size={1000}
        open={!!selected}
        onClose={() => setSelected(undefined)}
        destroyOnHidden
      >
        {selected && (
          <Tabs
            items={[
              {
                key: "render",
                label: "已发布页面",
                children: <PublishedPage id={selected} />,
              },
              {
                key: "versions",
                label: "版本与回退",
                children: <PageVersions id={selected} onDone={r.refresh} />,
              },
            ]}
          />
        )}
      </Drawer>
      <Drawer
        title="页面编排"
        size={960}
        open={editing}
        onClose={() => {
          if (!command.busy) setEditing(false);
        }}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          form={form}
          layout="vertical"
          initialValues={
            draft ?? {
              version: 1,
              sections: [
                { id: "campaigns", title: "营销活动", source: "CAMPAIGNS" },
              ],
              actions: [],
            }
          }
          onValuesChange={() => setPreview(undefined)}
          onFinish={async (v) => {
            if (
              (await command.run("/admin/ops-pages", body(v))) !== undefined
            ) {
              setEditing(false);
              form.resetFields();
              r.refresh();
            }
          }}
        >
          <div className="node-grid">
            <Fields
              fields={[
                { name: "pageId", label: "页面标识" },
                { name: "version", label: "版本", type: "number", min: 1 },
                { name: "title", label: "页面标题" },
              ]}
            />
          </div>
          <div className="form-section-title">数据组件</div>
          <Form.List name="sections">
            {(fields, { add, remove }) => (
              <>
                {fields.map((f) => (
                  <Card size="small" className="node-card" key={f.key}>
                    <Space align="start" wrap>
                      <Form.Item
                        name={[f.name, "id"]}
                        label="组件标识"
                        rules={[{ required: true }]}
                      >
                        <Input />
                      </Form.Item>
                      <Form.Item
                        name={[f.name, "title"]}
                        label="标题"
                        rules={[{ required: true }]}
                      >
                        <Input />
                      </Form.Item>
                      <Form.Item
                        name={[f.name, "source"]}
                        label="可信数据源"
                        rules={[{ required: true }]}
                      >
                        <Select style={{ width: 240 }} options={sources} />
                      </Form.Item>
                      <Button
                        onClick={() => remove(f.name)}
                        disabled={fields.length <= 1}
                      >
                        移除
                      </Button>
                    </Space>
                  </Card>
                ))}
                <Button disabled={fields.length >= 8} onClick={() => add()}>
                  添加数据组件
                </Button>
              </>
            )}
          </Form.List>
          <div className="form-section-title">业务动作</div>
          <Form.List name="actions">
            {(fields, { add, remove }) => (
              <>
                {fields.map((f) => (
                  <Card size="small" className="node-card" key={f.key}>
                    <Space align="start" wrap>
                      <Form.Item
                        name={[f.name, "id"]}
                        label="动作标识"
                        rules={[{ required: true }]}
                      >
                        <Input />
                      </Form.Item>
                      <Form.Item
                        name={[f.name, "label"]}
                        label="按钮文字"
                        rules={[{ required: true }]}
                      >
                        <Input />
                      </Form.Item>
                      <Form.Item
                        name={[f.name, "kind"]}
                        label="业务能力"
                        rules={[{ required: true }]}
                      >
                        <Select style={{ width: 180 }} options={actions} />
                      </Form.Item>
                      <Button onClick={() => remove(f.name)}>移除</Button>
                    </Space>
                  </Card>
                ))}
                <Button disabled={fields.length >= 4} onClick={() => add()}>
                  添加业务动作
                </Button>
              </>
            )}
          </Form.List>
          <Space className="section-actions">
            <Button
              loading={command.busy}
              onClick={async () => {
                try {
                  const v = await form.validateFields();
                  const result = await command.run<PageRender>(
                    "/admin/ops-pages/preview",
                    body(v),
                  );
                  if (result) setPreview(result);
                } catch {
                  /* 表单组件就地显示校验错误 */
                }
              }}
            >
              预览真实数据
            </Button>
            <Button type="primary" htmlType="submit" loading={command.busy}>
              保存页面草稿
            </Button>
          </Space>
        </Form>
        {preview && <RenderPage render={preview} refresh={() => {}} />}
      </Drawer>
    </>
  );
}
