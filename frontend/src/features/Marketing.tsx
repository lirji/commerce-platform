import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
} from "antd";
import { useState } from "react";
import type { Campaign, Governed, Rule } from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
import {
  ActionButton,
  Detail,
  ErrorNotice,
  Fields,
  PageHead,
  Status,
  initialDate,
  instant,
  money,
  type Values,
} from "../shared/ui";
import { CampaignPreview } from "./CampaignPreview";
import { CampaignEditor, Governance, RuleEditor } from "../shared/marketing";
export function Marketing({ kind, store }: { kind: string; store: string }) {
  const path = kind === "rules" ? "/admin/rules" : "/admin/campaigns";
  const resource =
    useResource<
      Governed<
        Campaign | { ruleId: string; version: number; name: string; rule: Rule }
      >[]
    >(path);
  const [detail, setDetail] = useState<unknown>();
  const [open, setOpen] = useState(false);
  const command = useCommand();
  return (
    <>
      <PageHead
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
      <Card>
        <Table
          rowKey={(r) =>
            "campaignId" in r.content ? r.content.campaignId : r.content.ruleId
          }
          dataSource={resource.data}
          loading={resource.loading}
          pagination={false}
          scroll={{ x: 720 }}
          columns={[
            { title: "名称", render: (_, r) => r.content.name },
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
              render: (_, r) => (
                <Space wrap>
                  <Button size="small" onClick={() => setDetail(r)}>
                    查看配置
                  </Button>
                  {"campaignId" in r.content && <><CampaignPreview campaign={r.content}/><CampaignEditor store={r.content.storeId} onDone={resource.refresh} initialCampaign={r.content} label="复制新版本"/></>}
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
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <Drawer
        title="版本配置"
        open={!!detail}
        onClose={() => setDetail(undefined)}
        width={680}
      >
        <Detail value={detail} />
      </Drawer>
      <Modal
        title="新建规则资产"
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={720}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
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
          <Button type="primary" htmlType="submit" loading={command.busy}>
            保存规则
          </Button>
        </Form>
      </Modal>
    </>
  );
}
