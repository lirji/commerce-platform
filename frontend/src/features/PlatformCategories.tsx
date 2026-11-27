import { Table } from "../shared/BusinessTable";
import { Button, Input, Space, Tag, Typography } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { RecordModal, RowActions } from "../shared/interactions";
import {
  CommandModal,
  ErrorNotice,
  PageHead,
  Workbench,
  time,
  type Field,
} from "../shared/ui";

const DIRECTORY = "/platform/commerce-categories";
const BRANCH = "BRANCH";
const LEAF = "LEAF";
const MAX_DEPTH = 3;
type Category = {
  code: string;
  parentCode?: string;
  name: string;
  kind: typeof BRANCH | typeof LEAF;
  depth: number;
  sortOrder: number;
  enabled: boolean;
  version: number;
};
type CategoryRow = Category & { available: boolean; children?: CategoryRow[] };
type History = Pick<Category, "name" | "version" | "sortOrder" | "enabled"> & {
  actorId: string;
  reason: string;
  changedAt: string;
};
const editFields: Field[] = [
  { name: "name", label: "类目名称" },
  {
    name: "sortOrder",
    label: "排序",
    type: "number",
    min: 0,
    max: 65535,
    initial: 10,
  },
];
const reason: Field = { name: "reason", label: "变更原因" };
const kind: Field = {
  name: "kind",
  label: "类目类型",
  type: "select",
  options: [
    { value: BRANCH, label: "分组" },
    { value: LEAF, label: "可引用末级" },
  ],
};

function branch(rows: Category[], query: string): CategoryRow[] {
  const byParent = new Map<string, Category[]>();
  for (const row of rows) {
    const parent = row.parentCode ?? "";
    const siblings = byParent.get(parent) ?? [];
    siblings.push(row);
    byParent.set(parent, siblings);
  }
  function children(parent = "", parentEnabled = true): CategoryRow[] {
    return (byParent.get(parent) ?? []).flatMap((row) => {
      const available = parentEnabled && row.enabled;
      const descendants = children(row.code, available);
      if (
        query &&
        !row.name.includes(query) &&
        !row.code.toLowerCase().includes(query) &&
        !descendants.length
      )
        return [];
      return [
        {
          ...row,
          available,
          ...(descendants.length ? { children: descendants } : {}),
        },
      ];
    });
  }
  return children();
}

function CreateCategory({
  parent,
  onDone,
}: {
  parent?: CategoryRow;
  onDone: () => void;
}) {
  const fixedKind = !parent
    ? BRANCH
    : parent.depth + 1 === MAX_DEPTH
      ? LEAF
      : undefined;
  return (
    <CommandModal
      title={parent ? "新增下级" : "新增一级类目"}
      path={DIRECTORY}
      fields={[
        {
          name: "code",
          label: "类目编码",
          help: "大写字母开头，支持数字和下划线。保存后编码固定。",
        },
        ...editFields,
        ...(fixedKind ? [] : [kind]),
        reason,
      ]}
      build={(values) => ({
        ...values,
        parentCode: parent?.code ?? null,
        kind: fixedKind ?? values.kind,
      })}
      onDone={onDone}
    >
      {parent && (
        <Typography.Text type="secondary">
          上级类目 · {parent.name}
        </Typography.Text>
      )}
    </CommandModal>
  );
}

/** 平台分类管理复用真实字典，类目操作按服务器返回的专岗许可显示。 */
export function PlatformCategories({
  initialQuery = "",
}: {
  initialQuery?: string;
}) {
  const directory = useResource<{ canEdit: boolean; items: Category[] }>(
    DIRECTORY,
  );
  const [query, setQuery] = useState(initialQuery);
  const [historyCode, setHistoryCode] = useState<string>();
  const history = useResource<History[]>(
    historyCode ? `${DIRECTORY}/${encode(historyCode)}/history` : null,
  );
  const canEdit = !!directory.data?.canEdit;
  return (
    <Workbench>
      <PageHead
        eyebrow="平台工具"
        title="平台分类字典"
        description="管理开店与商品分类共享的标准类目。停用分支会停止其下所有类目的新引用。"
        extra={
          <Space wrap>
            <Button onClick={directory.refresh}>刷新</Button>
            {canEdit && <CreateCategory onDone={directory.refresh} />}
          </Space>
        }
      />
      <ErrorNotice error={directory.error} onRetry={directory.refresh} />
      <Space orientation="vertical" style={{ width: "100%" }} size="middle">
        <Input
          allowClear
          aria-label="按编码或名称查找类目"
          placeholder="按编码或名称查找类目"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {directory.data && !canEdit && (
          <Typography.Text type="secondary">
            当前账号可查看字典。新增和变更需要类目维护专岗。
          </Typography.Text>
        )}
        {directory.data && (
          <Table<CategoryRow>
            key={query.trim().toLowerCase()}
            rowKey="code"
            dataSource={branch(
              directory.data.items,
              query.trim().toLowerCase(),
            )}
            expandable={{ defaultExpandAllRows: true }}
            pagination={false}
            scroll={{ x: 800 }}
            columns={[
              { title: "类目", dataIndex: "name" },
              { title: "编码", dataIndex: "code" },
              {
                title: "类型",
                render: (_, row) => (row.kind === BRANCH ? "分组" : "末级"),
              },
              { title: "排序", dataIndex: "sortOrder", width: 72 },
              {
                title: "状态",
                render: (_, row) => (
                  <Tag color={row.available ? "green" : "default"}>
                    {row.available
                      ? "启用"
                      : row.enabled
                        ? "上级已停用"
                        : "已停用"}
                  </Tag>
                ),
              },
              {
                title: "操作",
                width: 220,
                render: (_, row) => (
                  <RowActions>
                    <Button
                      type="link"
                      onClick={() => setHistoryCode(row.code)}
                    >
                      变更记录
                    </Button>
                    {canEdit && row.kind === BRANCH && row.available && (
                      <CreateCategory parent={row} onDone={directory.refresh} />
                    )}
                    {canEdit && (
                      <CommandModal
                        title="维护"
                        path={`${DIRECTORY}/${encode(row.code)}`}
                        fields={[
                          ...editFields,
                          {
                            name: "enabled",
                            label: "启用类目",
                            type: "switch",
                            required: false,
                          },
                          reason,
                        ]}
                        initialValues={{
                          name: row.name,
                          sortOrder: row.sortOrder,
                          enabled: row.enabled,
                        }}
                        build={(values) => ({
                          ...values,
                          expectedVersion: row.version,
                        })}
                        onDone={directory.refresh}
                      >
                        <Typography.Text type="secondary">
                          {row.name} · {row.code}
                        </Typography.Text>
                      </CommandModal>
                    )}
                  </RowActions>
                ),
              },
            ]}
          />
        )}
      </Space>
      <RecordModal
        title="类目变更记录"
        open={!!historyCode}
        onCancel={() => setHistoryCode(undefined)}
        footer={null}
      >
        <ErrorNotice error={history.error} onRetry={history.refresh} />
        <Table<History>
          rowKey="version"
          dataSource={history.data}
          loading={history.loading}
          pagination={false}
          scroll={{ x: 620 }}
          columns={[
            { title: "名称", dataIndex: "name" },
            { title: "版本", dataIndex: "version" },
            {
              title: "状态",
              render: (_, row) => (row.enabled ? "启用" : "停用"),
            },
            { title: "排序", dataIndex: "sortOrder" },
            { title: "操作者", dataIndex: "actorId" },
            { title: "原因", dataIndex: "reason" },
            { title: "时间", render: (_, row) => time(row.changedAt) },
          ]}
        />
      </RecordModal>
    </Workbench>
  );
}
