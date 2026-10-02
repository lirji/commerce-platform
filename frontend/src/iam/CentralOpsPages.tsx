import {
  Alert,
  App,
  Button,
  Card,
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
import { useState } from "react";
import { useResource, type request } from "../shared/api";
import { ErrorNotice, Fields, type Values } from "../shared/ui";
import type { Governed, PageDefinition, PageRender } from "../shared/contracts";
import { couponBody, couponFields } from "../shared/formSchemas";
import {
  CampaignDraftForm,
  buildCampaignDraft,
  type DraftFields,
} from "./CampaignDraftForm";
import { identifier } from "./operationsClient";
import { useOperationsCommand } from "./operationsCommand";
import {
  OperationsWorkspace,
  OperationAction,
  OperationResult,
  type OperationsProps,
} from "./OperationsWorkspace";
type Dirty = (key: string, value: boolean) => void;
const ids = [
    { required: true, pattern: identifier, message: "请输入1—64位业务编号" },
  ],
  sources = ["CAMPAIGNS", "JOURNEYS", "BUDGETS", "COUPONS", "ENTITLEMENTS"],
  kinds = ["CREATE_CAMPAIGN", "CREATE_COUPON", "ENROLL_JOURNEY"];
function DefinitionAction({
  client,
  dirty,
  action,
}: {
  client: typeof request;
  dirty: Dirty;
  action: "create" | "preview";
}) {
  const access = useResource<{ allowed: boolean }>(
      `/operations/ops-pages/${action}-access`,
    ),
    [open, setOpen] = useState(false),
    [changed, setChanged] = useState(false),
    [result, setResult] = useState<unknown>();
  const [form] = Form.useForm<PageDefinition>();
  const { modal } = App.useApp();
  const command = useOperationsCommand(client, "ops-pages", (v) =>
    dirty(action, v),
  );
  function close() {
    if (command.busy || command.frozen) return;
    const done = () => {
      setOpen(false);
      dirty(action, false);
      setChanged(false);
      command.clear();
    };
    if (changed) modal.confirm({ title: "放弃页面输入？", onOk: done });
    else done();
  }
  async function submit() {
    const value = command.frozen
      ? await command.run<unknown>(
          "/admin/ops-pages",
          undefined,
          action,
          action === "create",
        )
      : await command.run<unknown>(
          action === "create" ? "/admin/ops-pages" : "/admin/ops-pages/preview",
          await form.validateFields(),
          action,
          action === "create",
        );
    if (value !== undefined) {
      setResult(value);
      setChanged(false);
    }
  }
  return (
    <>
      <Space wrap>
        <Button
          disabled={
            access.loading || !!access.error || access.data?.allowed !== true
          }
          onClick={() => {
            setOpen(true);
            setResult(undefined);
            form.resetFields();
          }}
        >
          {action === "create" ? "创建页面版本" : "只读预览页面"}
        </Button>
        <Button size="small" onClick={access.refresh}>
          核验{action === "create" ? "创建" : "预览"}资格
        </Button>
      </Space>
      <ErrorNotice error={access.error} />
      <Modal
        className="operations-modal"
        open={open}
        centered
        title={action === "create" ? "创建不可变页面版本" : "真实数据只读预览"}
        width={820}
        styles={{ body: { maxHeight: "67vh", overflowY: "auto" } }}
        maskClosable={false}
        keyboard={!command.busy && !command.frozen}
        closable={!command.busy && !command.frozen}
        onCancel={close}
        footer={
          <Space wrap>
            <Button disabled={command.busy || command.frozen} onClick={close}>
              关闭
            </Button>
            <Button
              type="primary"
              loading={command.busy}
              disabled={result !== undefined}
              onClick={() => void submit().catch(() => {})}
            >
              {command.frozen
                ? "按原意图重试"
                : action === "create"
                  ? "创建版本"
                  : "读取预览"}
            </Button>
          </Space>
        }
      >
        <ErrorNotice error={command.error} />
        {command.frozen && (
          <Alert type="warning" title="结果未知，原定义与命令意图已冻结" />
        )}
        <Form
          form={form}
          layout="vertical"
          disabled={command.busy || command.frozen || result !== undefined}
          initialValues={{ version: 1, sections: [{}], actions: [] }}
          onValuesChange={() => {
            setChanged(true);
            dirty(action, true);
          }}
        >
          <Form.Item name="pageId" label="页面编号" rules={ids}>
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="version"
            label="新内容版本"
            rules={[{ required: true, type: "integer", min: 1 }]}
          >
            <InputNumber min={1} />
          </Form.Item>
          <Form.Item
            name="title"
            label="页面标题"
            rules={[{ required: true, max: 128 }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="storeId" label="真实门店编号" rules={ids}>
            <Input maxLength={64} />
          </Form.Item>
          <Typography.Title level={5}>读取区块 · 最多8个</Typography.Title>
          <Form.List name="sections">
            {(fields, { add, remove }) => (
              <>
                {fields.map((f) => (
                  <Card size="small" key={f.key} style={{ marginBottom: 12 }}>
                    <Form.Item
                      name={[f.name, "id"]}
                      label="区块编号"
                      rules={ids}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name={[f.name, "title"]}
                      label="区块标题"
                      rules={[{ required: true, max: 128 }]}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name={[f.name, "source"]}
                      label="已有数据来源"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={sources.map((value) => ({
                          value,
                          label: value,
                        }))}
                      />
                    </Form.Item>
                    <Button
                      disabled={fields.length <= 1}
                      onClick={() => remove(f.name)}
                    >
                      移除区块
                    </Button>
                  </Card>
                ))}
                <Button disabled={fields.length >= 8} onClick={() => add({})}>
                  增加读取区块
                </Button>
              </>
            )}
          </Form.List>
          <Typography.Title level={5}>内嵌业务动作 · 最多4个</Typography.Title>
          <Form.List name="actions">
            {(fields, { add, remove }) => (
              <>
                {fields.map((f) => (
                  <Card size="small" key={f.key} style={{ marginBottom: 12 }}>
                    <Form.Item
                      name={[f.name, "id"]}
                      label="动作编号"
                      rules={ids}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name={[f.name, "label"]}
                      label="动作名称"
                      rules={[{ required: true, max: 128 }]}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name={[f.name, "kind"]}
                      label="既有业务动作"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={kinds.map((value) => ({
                          value,
                          label: value,
                        }))}
                      />
                    </Form.Item>
                    <Button onClick={() => remove(f.name)}>移除动作</Button>
                  </Card>
                ))}
                <Button disabled={fields.length >= 4} onClick={() => add({})}>
                  增加业务动作
                </Button>
              </>
            )}
          </Form.List>
        </Form>
        {result !== undefined &&
          (action === "preview" ? (
            <RenderView value={result as PageRender} />
          ) : (
            <OperationResult value={result} />
          ))}
      </Modal>
    </>
  );
}
function RenderView({ value }: { value: PageRender }) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title={`${value.page.content.title} · 版本${value.page.content.version}${value.preview ? " · 只读预览" : " · 当前发布"}`}
        description="每个区块独立核验真实来源读取能力；没有内嵌动作权限时不能提交业务效果。"
      />
      {value.page.content.sections.map((s) => {
        const rows = value.data.find((d) => d.id === s.id)?.rows as
          Record<string, unknown>[] | undefined;
        return (
          <Card key={s.id} title={s.title}>
            <Table<Record<string, unknown>>
              rowKey={(r, i) =>
                String(
                  r.campaignId ??
                    r.journeyId ??
                    r.budgetId ??
                    r.definitionId ??
                    i,
                )
              }
              dataSource={rows}
              pagination={false}
              scroll={{ x: 480 }}
              columns={[
                {
                  title: "业务编号",
                  render: (_, r) =>
                    String(
                      r.campaignId ??
                        r.journeyId ??
                        r.budgetId ??
                        r.definitionId ??
                        "—",
                    ),
                },
                {
                  title: "名称",
                  render: (_, r) =>
                    String(
                      r.name ??
                        (r.content as Record<string, unknown> | undefined)
                          ?.name ??
                        "—",
                    ),
                },
                {
                  title: "当前状态",
                  render: (_, r) => String(r.status ?? "—"),
                },
                {
                  title: "内容版本",
                  render: (_, r) =>
                    String(
                      r.version ??
                        (r.content as Record<string, unknown> | undefined)
                          ?.version ??
                        "—",
                    ),
                },
              ]}
            />
          </Card>
        );
      })}
    </Space>
  );
}
function EmbeddedAction({
  client,
  dirty,
  page,
}: {
  client: typeof request;
  dirty: Dirty;
  page: PageDefinition;
}) {
  const access = useResource<{ allowed: boolean }>(
      "/operations/ops-pages/execute-access",
    ),
    [choice, setChoice] = useState<PageDefinition["actions"][number]>(),
    [result, setResult] = useState<unknown>();
  const [campaign] = Form.useForm<DraftFields>(),
    [other] = Form.useForm();
  const command = useOperationsCommand(client, "ops-pages", (v) =>
    dirty("execute", v),
  );
  async function submit() {
    if (!choice) return;
    let body: unknown;
    if (!command.frozen) {
      body =
        choice.kind === "CREATE_CAMPAIGN"
          ? { campaign: buildCampaignDraft(await campaign.validateFields()) }
          : choice.kind === "CREATE_COUPON"
            ? { coupon: couponBody(await other.validateFields(), page.storeId) }
            : { enrollment: await other.validateFields() };
    }
    const value = await command.run(
      `/admin/ops-pages/${page.pageId}/${page.version}/actions/${choice.id}`,
      body,
      "execute",
    );
    if (value !== undefined) setResult(value);
  }
  return (
    <>
      <ErrorNotice error={access.error} />
      <Space wrap>
        {page.actions.map((a) => (
          <Button
            key={a.id}
            disabled={
              access.loading || !!access.error || access.data?.allowed !== true
            }
            onClick={() => {
              setChoice(a);
              setResult(undefined);
              other.resetFields();
              campaign.resetFields();
              campaign.setFieldsValue({ storeId: page.storeId });
            }}
          >
            {a.label}
          </Button>
        ))}
        <Button size="small" onClick={access.refresh}>
          核验内嵌动作资格
        </Button>
      </Space>
      <Modal
        className="operations-modal"
        open={!!choice}
        centered
        title={choice?.label}
        width={820}
        styles={{ body: { maxHeight: "66vh", overflowY: "auto" } }}
        closable={!command.busy && !command.frozen}
        keyboard={!command.busy && !command.frozen}
        maskClosable={false}
        onCancel={() => {
          if (!command.busy && !command.frozen) {
            setChoice(undefined);
            dirty("execute", false);
            command.clear();
          }
        }}
        footer={
          <Space wrap>
            <Button
              disabled={command.busy || command.frozen}
              onClick={() => {
                setChoice(undefined);
                dirty("execute", false);
                command.clear();
              }}
            >
              关闭
            </Button>
            <Button
              type="primary"
              loading={command.busy}
              disabled={result !== undefined}
              onClick={() => void submit().catch(() => {})}
            >
              {command.frozen ? "按原意图重试" : "提交实际业务动作"}
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          title={`实际发布页面 ${page.pageId} / ${page.version} · 门店 ${page.storeId}`}
          description="提交将签发准确子业务能力，页面权限不会代替活动、券或旅程权限。"
        />
        <ErrorNotice error={command.error} />
        {command.frozen && (
          <Alert
            type="warning"
            title="结果未知，原页面、动作、业务输入及键已冻结"
          />
        )}
        {choice?.kind === "CREATE_CAMPAIGN" ? (
          <CampaignDraftForm
            form={campaign}
            disabled={command.busy || command.frozen || result !== undefined}
            onChange={() => dirty("execute", true)}
            onFinish={() => void submit()}
          />
        ) : (
          <Form
            form={other}
            layout="vertical"
            disabled={command.busy || command.frozen || result !== undefined}
            onValuesChange={() => dirty("execute", true)}
          >
            {choice?.kind === "CREATE_COUPON" ? (
              <Fields fields={couponFields} />
            ) : (
              <>
                <Form.Item name="journeyId" label="实际旅程编号" rules={ids}>
                  <Input />
                </Form.Item>
                <Form.Item
                  name="version"
                  label="真实定义版本"
                  rules={[{ required: true, type: "integer", min: 1 }]}
                >
                  <InputNumber min={1} />
                </Form.Item>
                <Form.Item name="memberId" label="原会员编号" rules={ids}>
                  <Input />
                </Form.Item>
                <Form.Item name="eventKey" label="原事件键" rules={ids}>
                  <Input />
                </Form.Item>
              </>
            )}
          </Form>
        )}
        {result !== undefined && <OperationResult value={result} />}
      </Modal>
    </>
  );
}
/** 执行only岗位可直接输入真实目标；发布内容与准确子能力仍由Owner校验，列表read不成为动作前置。 */
function DirectExecution({
  client,
  dirty,
}: {
  client: typeof request;
  dirty: Dirty;
}) {
  const [target, setTarget] = useState<PageDefinition>(),
    [pending, setPending] = useState(false);
  return (
    <Card title="直接执行已发布页面动作">
      <Form
        layout="vertical"
        disabled={!!target}
        onFinish={(v) =>
          setTarget({
            pageId: v.pageId,
            version: v.version,
            storeId: v.storeId,
            title: "原发布页面",
            sections: [],
            actions: [
              { id: v.actionId, label: "执行原页面动作", kind: v.kind },
            ],
          })
        }
      >
        <Form.Item name="pageId" label="实际页面编号" rules={ids}>
          <Input />
        </Form.Item>
        <Form.Item
          name="version"
          label="实际发布内容版本"
          rules={[{ required: true, type: "integer", min: 1 }]}
        >
          <InputNumber min={1} />
        </Form.Item>
        <Form.Item name="storeId" label="页面实际门店编号" rules={ids}>
          <Input />
        </Form.Item>
        <Form.Item name="actionId" label="原页面声明的动作编号" rules={ids}>
          <Input />
        </Form.Item>
        <Form.Item name="kind" label="原动作类型" rules={[{ required: true }]}>
          <Select options={kinds.map((value) => ({ value, label: value }))} />
        </Form.Item>
        <Button htmlType="submit">填写原业务动作输入</Button>
      </Form>
      {target && (
        <>
          <EmbeddedAction
            client={client}
            dirty={(key, value) => {
              setPending(value);
              dirty(key, value);
            }}
            page={target}
          />
          <Button disabled={pending} onClick={() => setTarget(undefined)}>
            重新选择页面动作
          </Button>
          <Typography.Paragraph type="secondary">
            目标与类型必须匹配实际发布页面；任何不一致由实际提交拒绝。
          </Typography.Paragraph>
        </>
      )}
    </Card>
  );
}
function PageRead({ client, dirty }: { client: typeof request; dirty: Dirty }) {
  const [after, setAfter] = useState(""),
    [target, setTarget] = useState("");
  const list = useResource<Governed<PageDefinition>[]>(
      `/admin/ops-pages?after=${after}&limit=50`,
    ),
    versions = useResource<Governed<PageDefinition>[]>(
      target ? `/admin/ops-pages/${target}/versions` : null,
    ),
    render = useResource<PageRender>(
      target ? `/admin/ops-pages/${target}/render` : null,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card
        title="页面最新版本"
        extra={<Button onClick={list.refresh}>刷新列表</Button>}
      >
        <ErrorNotice error={list.error} />
        <Table<Governed<PageDefinition>>
          rowKey={(r) => r.content.pageId}
          loading={list.loading}
          dataSource={list.data}
          pagination={false}
          scroll={{ x: 650 }}
          columns={[
            { title: "页面", render: (_, r) => r.content.title },
            { title: "编号", render: (_, r) => r.content.pageId },
            { title: "门店", render: (_, r) => r.content.storeId },
            { title: "内容版本", render: (_, r) => r.content.version },
            {
              title: "状态",
              dataIndex: "status",
              render: (v) => <Tag>{v}</Tag>,
            },
            {
              title: "详情",
              render: (_, r) => (
                <Button onClick={() => setTarget(r.content.pageId)}>
                  读取版本与发布页
                </Button>
              ),
            },
          ]}
        />
        <Space wrap style={{ marginTop: 12 }}>
          <Button disabled={!after} onClick={() => setAfter("")}>
            首批
          </Button>
          <Button
            disabled={list.data?.length !== 50 || list.loading}
            onClick={() => setAfter(list.data?.at(-1)?.content.pageId ?? "")}
          >
            下一批
          </Button>
        </Space>
      </Card>
      <Card title="直接读取发布页面">
        <Form layout="inline" onFinish={(v) => setTarget(v.pageId)}>
          <Form.Item name="pageId" label="实际页面编号" rules={ids}>
            <Input maxLength={64} />
          </Form.Item>
          <Button htmlType="submit">读取发布内容</Button>
        </Form>
        <ErrorNotice error={versions.error} />
        {versions.data && (
          <Table<Governed<PageDefinition>>
            rowKey={(r) => String(r.content.version)}
            dataSource={versions.data}
            pagination={false}
            scroll={{ x: 540 }}
            columns={[
              { title: "版本", render: (_, r) => r.content.version },
              { title: "状态", dataIndex: "status" },
              { title: "控制版本", dataIndex: "lockVersion" },
            ]}
          />
        )}
        <ErrorNotice error={render.error} />
        {render.data && (
          <>
            <RenderView value={render.data} />
            <EmbeddedAction
              client={client}
              dirty={dirty}
              page={render.data.page.content}
            />
          </>
        )}
      </Card>
    </Space>
  );
}
export default function CentralOpsPages(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="ops-pages"
      title="运营页面治理"
      description="不可变页面内容、审批发布与实际业务动作各自独立判权，真实聚合不会扩大来源权限。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="版本与治理操作">
            <Space wrap>
              <DefinitionAction client={client} dirty={dirty} action="create" />
              <DefinitionAction
                client={client}
                dirty={dirty}
                action="preview"
              />
              {(
                [
                  "submit",
                  "approve",
                  "reject",
                  "publish",
                  "pause",
                  "rollback",
                ] as const
              ).map((action) => (
                <OperationAction
                  key={action}
                  client={client}
                  dirty={dirty}
                  family="ops-pages"
                  action={action}
                  label={
                    {
                      submit: "提交审核",
                      approve: "批准版本",
                      reject: "驳回版本",
                      publish: "发布版本",
                      pause: "暂停版本",
                      rollback: "回退历史版本",
                    }[action]
                  }
                  fields={[
                    { name: "pageId", label: "原页面编号", kind: "id" },
                    {
                      name: "version",
                      label: "原内容版本",
                      kind: "number",
                      min: 1,
                    },
                    {
                      name: "expectedVersion",
                      label: "当前控制版本",
                      kind: "number",
                      min: 0,
                    },
                  ]}
                  path={(v) =>
                    `/admin/ops-pages/${v.pageId}/${v.version}/${action}`
                  }
                  body={(v) => ({ expectedVersion: v.expectedVersion })}
                  note="仅允许原治理状态的合法迁移；历史回退仍要核对曾发布版本、实际门店及控制版本。"
                />
              ))}
            </Space>
          </Card>
          <DirectExecution client={client} dirty={dirty} />
          <PageRead client={client} dirty={dirty} />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
