import { useRouteState } from "../shared/routeState";
import { Button, Form, Modal, Table } from "antd";
import { useState } from "react";
import type { Campaign, Governed, Rule } from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
import {
  ActionButton,
  RecordDrawer as Drawer,
  RowActions,
  FormActions,
  useDirtyClose,
  ErrorNotice,
  Fields,
  ListPanel,
  PageHead,
  PrimaryCell,
  Status,
  Workbench,
  money,
} from "../shared/ui";
import { MarketingDetails } from "./MarketingDetails";
import { CampaignPreview } from "./CampaignPreview";
import { CampaignEditor, Governance, RuleEditor } from "../shared/marketing";
export function Marketing({ kind, store }: { kind: string; store: string }) {
  const [after, setAfter] = useRouteState("after", "");
  const path = kind === "rules" ? "/admin/rules" : "/admin/campaigns";
  const resource = useResource<
    Governed<
      Campaign | { ruleId: string; version: number; name: string; rule: Rule }
    >[]
  >(`${path}?after=${encode(after)}`);
  const last = resource.data?.at(-1)?.content;
  const [detail, setDetail] =
    useState<
      Governed<
        Campaign | { ruleId: string; version: number; name: string; rule: Rule }
      >
    >();
  const [open, setOpen] = useState(false);
  const command = useCommand();
  const [form] = Form.useForm();
  const closing = useDirtyClose(form, command.busy, () => setOpen(false));
  return (
    <Workbench>
      {closing.contextHolder}
      <PageHead
        eyebrow="营销与旅程"
        title={kind === "rules" ? "动态规则资产" : "营销活动"}
        description={
          kind === "rules"
            ? "可信字段与有界条件树，已发布版本不可修改。"
            : "围绕会员资格、优惠和权益，管理活动全生命周期。"
        }
        extra={
          <>
            <Button onClick={resource.refresh}>刷新</Button>
            {kind === "rules" ? (
              <Button type="primary" onClick={() => setOpen(true)}>
                新建规则
              </Button>
            ) : (
              <CampaignEditor store={store} onDone={resource.refresh} />
            )}
          </>
        }
      />
      <ErrorNotice error={resource.error} />
      <ListPanel
        count={resource.data?.length ?? 0}
        after={after}
        onHome={() => setAfter("")}
        onNext={() => {
          if (last)
            setAfter("campaignId" in last ? last.campaignId : last.ruleId);
        }}
      >
        <Table
          rowKey={(r) =>
            "campaignId" in r.content ? r.content.campaignId : r.content.ruleId
          }
          dataSource={resource.data}
          loading={resource.loading}
          pagination={false}
          scroll={{ x: 720 }}
          columns={[
            {
              title: "名称",
              render: (_, r) => (
                <PrimaryCell
                  title={r.content.name}
                  subtitle={
                    "campaignId" in r.content
                      ? r.content.campaignId
                      : r.content.ruleId
                  }
                />
              ),
            },
            { title: "版本", render: (_, r) => r.content.version },
            {
              title: "优惠",
              render: (_, r) =>
                "discountAmount" in r.content
                  ? money(r.content.discountAmount)
                  : "—",
            },
            {
              title: "状态",
              dataIndex: "status",
              render: (v: string) => <Status value={v} />,
            },
            {
              title: "操作",
              width: 350,
              className: "row-actions-cell",
              render: (_, r) => (
                <RowActions>
                  <Button type="link" size="small" onClick={() => setDetail(r)}>
                    查看配置
                  </Button>
                  {"campaignId" in r.content && (
                    <>
                      <CampaignPreview campaign={r.content} />
                      <CampaignEditor
                        store={r.content.storeId}
                        onDone={resource.refresh}
                        initialCampaign={r.content}
                        label="复制新版本"
                      />
                    </>
                  )}
                  {"campaignId" in r.content ? (
                    <Governance
                      base={path}
                      id={r.content.campaignId}
                      version={r.content.version}
                      status={r.status}
                      lockVersion={r.lockVersion}
                      legacy={!r.content.policy}
                      onDone={resource.refresh}
                    />
                  ) : (
                    r.status === "DRAFT" && (
                      <ActionButton
                        label="发布规则"
                        path={
                          path +
                          "/" +
                          encode(r.content.ruleId) +
                          "/" +
                          r.content.version +
                          "/publish"
                        }
                        onDone={resource.refresh}
                      />
                    )
                  )}
                </RowActions>
              ),
            },
          ]}
        />
      </ListPanel>
      <Drawer
        className="record-drawer"
        title="版本配置"
        open={!!detail}
        onClose={() => setDetail(undefined)}
        width={720}
      >
        {detail && <MarketingDetails record={detail} />}
      </Drawer>
      <Modal
        title="新建规则资产"
        open={open}
        onCancel={closing.requestClose}
        keyboard={!command.busy}
        mask={{ closable: !command.busy }}
        footer={
          <FormActions onCancel={closing.requestClose} busy={command.busy}>
            <Button
              type="primary"
              loading={command.busy}
              onClick={() => form.submit()}
            >
              保存规则
            </Button>
          </FormActions>
        }
        width={720}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            version: 1,
            rule: {
              kind: "COMPARE",
              field: "memberLevel",
              operator: "EQ",
              valueType: "TEXT",
              value: "",
            },
          }}
          onFinish={async (v) => {
            if ((await command.run("/admin/rules", v)) !== undefined) {
              setOpen(false);
              resource.refresh();
            }
          }}
        >
          <Fields
            fields={[
              { name: "ruleId", label: "规则标识" },
              { name: "version", label: "内容版本", type: "number", min: 1 },
              { name: "name", label: "规则名称" },
            ]}
          />
          <Form.Item label="规则条件" name="rule">
            <RuleEditor />
          </Form.Item>
        </Form>
      </Modal>
    </Workbench>
  );
}
