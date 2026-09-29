import { Alert, App, Button, Descriptions, Space, Spin } from "antd";
import { useRef, useState } from "react";
import { central, CentralError, useCentral, type Context } from "./api";
const State = {
  SUBMITTED: "SUBMITTED",
  RUNNING: "RUNNING",
  COMPLETED: "COMPLETED",
} as const;
type Job = {
  id: string;
  resourceType: string;
  state: string;
  rowCount: number;
  version: number;
  expiresAt: string;
};
/** URL任务只定位，后台每次重新检查本人、代际、范围与固定授权期限。 */
export function ProductExport({
  context,
  search,
  jobId,
  revision,
  onJob,
  refresh,
}: {
  context: Context;
  search: string;
  jobId: string | null;
  revision: number;
  onJob: (id: string | null) => void;
  refresh: () => void;
}) {
  const access = useCentral<{ export: boolean }>(
    context,
    "/export-access",
    revision,
  );
  const job = useCentral<Job>(
    context,
    jobId ? `/exports/${encodeURIComponent(jobId)}` : null,
    revision,
  );
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<Error>();
  const running = useRef(false);
  const intent = useRef<{ path: string; key: string } | undefined>(undefined);
  const [unknown, setUnknown] = useState(false);
  const { message } = App.useApp();
  const portal = portalRequest(context.tenant);
  async function command(path: string) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    if (!intent.current) intent.current = { path, key: crypto.randomUUID() };
    try {
      const result = await central<Job>(
        context,
        intent.current.path,
        {},
        intent.current.key,
      );
      intent.current = undefined;
      setUnknown(false);
      onJob(result.id);
      refresh();
      message.success(
        result.state === State.COMPLETED
          ? "导出内容已生成，下载仍需核验权限"
          : "导出进度已保存",
      );
    } catch (e) {
      setError(e as Error);
      if (e instanceof CentralError && e.status < 500) {
        intent.current = undefined;
        setUnknown(false);
        refresh();
      } else setUnknown(true);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  async function download() {
    if (!jobId || running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const data = await central(
        context,
        `/exports/${encodeURIComponent(jobId)}/download`,
      );
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "products.json";
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(e as Error);
      refresh();
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  const failure = error ?? access.error ?? job.error;
  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      {failure && <Alert type="error" showIcon title={failure.message} />}
      <Space wrap>
        {portal && (
          <Button href={portal} target="_blank" rel="noopener noreferrer">
            申请限时导出
          </Button>
        )}
        {access.data?.export && !access.error && (
          <Button
            disabled={busy || unknown}
            onClick={() =>
              command(`/exports?search=${encodeURIComponent(search)}`)
            }
          >
            新建商品导出
          </Button>
        )}
        <Button disabled={busy || unknown} onClick={refresh}>
          刷新导出权限与进度
        </Button>
      </Space>
      {!access.loading && !access.error && !access.data?.export && (
        <Alert type="info" title="当前可查询商品；导出需申请独立的限时权限" />
      )}
      {unknown && (
        <Alert
          type="warning"
          title="提交结果未知，请原样重试确认"
          action={
            <Button
              loading={busy}
              onClick={() => command(intent.current!.path)}
            >
              原样重试导出命令
            </Button>
          }
        />
      )}
      {jobId && (
        <>
          {job.loading ? (
            <Spin />
          ) : job.data && !job.error ? (
            <>
              <Descriptions
                column={1}
                bordered
                items={[
                  { key: "id", label: "导出任务", children: job.data.id },
                  {
                    key: "state",
                    label: "执行状态",
                    children:
                      job.data.state === State.SUBMITTED
                        ? "已提交，尚未开始"
                        : job.data.state === State.RUNNING
                          ? "处理中"
                          : job.data.state === State.COMPLETED
                            ? "内容已生成"
                            : job.data.state,
                  },
                  {
                    key: "count",
                    label: "已生成行数",
                    children: job.data.rowCount,
                  },
                  {
                    key: "expires",
                    label: "任务有效期",
                    children: new Date(job.data.expiresAt).toLocaleString(),
                  },
                ]}
              />
              <Space wrap>
                {job.data.state === State.SUBMITTED && (
                  <Button
                    type="primary"
                    disabled={unknown}
                    loading={busy}
                    onClick={() =>
                      command(
                        `/exports/${jobId}/start?version=${job.data!.version}`,
                      )
                    }
                  >
                    开始导出
                  </Button>
                )}
                {job.data.state === State.RUNNING && (
                  <Button
                    type="primary"
                    disabled={unknown}
                    loading={busy}
                    onClick={() =>
                      command(
                        `/exports/${jobId}/advance?version=${job.data!.version}`,
                      )
                    }
                  >
                    继续处理（每次最多50行）
                  </Button>
                )}
                {job.data.state === State.COMPLETED && (
                  <Button type="primary" loading={busy} onClick={download}>
                    下载商品文件
                  </Button>
                )}
                <Button disabled={busy || unknown} onClick={() => onJob(null)}>
                  关闭任务详情
                </Button>
              </Space>
            </>
          ) : null}
        </>
      )}
    </Space>
  );
}
/** 跳转Origin来自固定构建配置，只附上下文；申请人和权限由工作台重新验证。 */
function portalRequest(tenant: string): string | undefined {
  const base = import.meta.env.VITE_IAM_PORTAL_URL;
  if (!base) return undefined;
  try {
    const url = new URL(base);
    if (
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      (url.protocol !== "https:" &&
        !(
          url.protocol === "http:" &&
          ["localhost", "127.0.0.1"].includes(url.hostname)
        ))
    )
      return undefined;
    url.pathname = "/governance/requests";
    url.search = new URLSearchParams({
      tenant,
      application: "commerce",
      environment: import.meta.env.VITE_IAM_ENVIRONMENT ?? "test",
    }).toString();
    return url.href;
  } catch {
    return undefined;
  }
}
