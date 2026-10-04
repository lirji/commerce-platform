import { CursorBack } from "../shared/pagination";
import { useCursorState } from "../shared/routeState";
import { PagerActions } from "../shared/interactions";
import { RecordModal, RowActions } from "../shared/interactions";
import { Button, Dropdown, Table } from "antd";
import { MemberBehavior } from "./MemberBehavior";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, time } from "../shared/ui";

/** 资料与状态分开提交；冲突后刷新主列表再操作，避免覆盖他人决定。 */
export function MemberActions({
  row,
  onDone,
  onRecord,
}: {
  row: Record<string, unknown>;
  onDone: () => void;
  onRecord?: () => void;
}) {
  const [detail, setDetail] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [statusOpen, setStatusOpen] = useState(false);
  const [after, setAfter] = useCursorState("MemberActions.after", 0);
  const base = "/admin/members/" + encode(String(row.memberId));
  const history = useResource<Record<string, unknown>[]>(
    historyOpen ? base + "/history?after=" + after : null,
  );
  const closed = row.status === "CLOSED";
  const done = () => {
    onDone();
    history.refresh();
  };
  return (
    <RowActions>
      <Button type="link" onClick={() => setDetail(true)}>
        会员详情
      </Button>
      <Dropdown
        trigger={["click"]}
        menu={{
          items: [
            ...(onRecord
              ? [{ key: "record", label: "档案记录", onClick: onRecord }]
              : []),
            {
              key: "profile",
              label: "编辑资料",
              disabled: closed,
              onClick: () => setProfileOpen(true),
            },
            {
              key: "status",
              label: "变更状态",
              disabled: closed,
              onClick: () => setStatusOpen(true),
            },
            {
              key: "history",
              label: "变更记录",
              onClick: () => {
                setAfter(0);
                setHistoryOpen(true);
              },
            },
          ],
        }}
      >
        <Button type="link">更多</Button>
      </Dropdown>
      <RecordModal
        className="record-modal"
        title="会员经营详情"
        open={detail}
        onCancel={() => setDetail(false)}
        width={760}
        destroyOnHidden
      >
        {detail && <MemberBehavior admin memberId={String(row.memberId)} />}
      </RecordModal>
      <CommandModal
        hideButton
        open={profileOpen}
        onOpenChange={setProfileOpen}
        title="编辑资料"
        path={base + "/profile"}
        disabled={closed}
        fields={[
          { name: "value", label: "显示名称", initial: row.displayName },
          { name: "reason", label: "变更原因" },
        ]}
        build={(v) => ({ ...v, expectedVersion: row.version })}
        onDone={done}
      />
      <CommandModal
        hideButton
        open={statusOpen}
        onOpenChange={setStatusOpen}
        title="变更状态"
        path={base + "/status"}
        disabled={closed}
        fields={[
          {
            name: "value",
            label: "目标状态",
            type: "select",
            options: [
              ...(row.status === "ACTIVE"
                ? [{ label: "冻结", value: "FROZEN" }]
                : [{ label: "恢复正常", value: "ACTIVE" }]),
              {
                label: "注销（不可恢复，保留交易记录）",
                value: "CLOSED",
              },
            ],
          },
          { name: "reason", label: "变更原因" },
        ]}
        build={(v) => ({ ...v, expectedVersion: row.version })}
        onDone={done}
      />
      <RecordModal
        className="record-modal"
        title="会员变更记录"
        open={historyOpen}
        onCancel={() => setHistoryOpen(false)}
        width={760}
      >
        <ErrorNotice error={history.error} onRetry={history.refresh} />
        <Button onClick={history.refresh}>刷新</Button>
        <Table
          rowKey="version"
          dataSource={history.data}
          loading={history.loading}
          pagination={false}
          columns={[
            { title: "版本", dataIndex: "version" },
            { title: "变更前", dataIndex: "beforeValue" },
            { title: "变更后", dataIndex: "afterValue" },
            { title: "原因", dataIndex: "reason" },
            { title: "操作人", dataIndex: "actorId" },
            { title: "时间", dataIndex: "createdAt", render: time },
          ]}
        />
        <PagerActions>
          <CursorBack
            name={"MemberActions.after"}
            after={after}
            onPrevious={setAfter}
            initial={0}
            disabled={history.loading || !!history.error}
            count={history.data?.length}
            pageSize={50}
          />
          <Button disabled={after === 0} onClick={() => setAfter(0)}>
            最早记录
          </Button>
          <Button
            disabled={
              history.loading || !!history.error || history.data?.length !== 50
            }
            onClick={() => setAfter(Number(history.data!.at(-1)!.version))}
          >
            下一页
          </Button>
        </PagerActions>
      </RecordModal>
    </RowActions>
  );
}
