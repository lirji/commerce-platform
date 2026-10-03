import { RecordModal } from "../shared/interactions";
import { Avatar, Button, Grid, Layout, Menu, Space, Typography } from "antd";
import { useState, type ReactNode } from "react";
import { Icon, type IconName } from "../shared/Icon";
import { WorkspaceBrand, WorkspaceSearch } from "../shared/WorkspaceChrome";
import { centralGroups } from "./navigation";

/** 壳层仅承载导航和视觉，原页面继续负责独立权限、凭据及未保存/未知结果保护。 */
export function CentralShell({
  tenant,
  subject,
  children,
}: {
  tenant: string;
  subject: string;
  children: ReactNode;
}) {
  const screens = Grid.useBreakpoint();
  const compact = screens.md === false;
  const [open, setOpen] = useState(false);
  const collaboration = location.pathname.startsWith("/collaboration/");
  const active = centralGroups.find((g) =>
    g.pages.some(([path]) => path === location.pathname),
  );
  const target = (path: string) => {
    const query = new URLSearchParams({ tenant_id: tenant });
    const environment = new URLSearchParams(location.search).get("environment");
    if (environment) query.set("environment", environment);
    return path + "?" + query;
  };
  const navigation = (
    <Menu
      mode="inline"
      selectedKeys={[location.pathname]}
      defaultOpenKeys={active ? [active.key] : []}
      items={centralGroups.map((g) => ({
        key: g.key,
        label: g.label,
        icon: <Icon name={g.key as IconName} />,
        children: g.pages.map(([path, label]) => ({
          key: path,
          label: <a href={target(path)}>{label}</a>,
        })),
      }))}
    />
  );
  return (
    <Layout className="app admin-app central-app">
      {!compact && !collaboration && (
        <Layout.Sider width={236} theme="light" className="sidebar">
          <WorkspaceBrand />
          <div className="nav-caption">企业经营工作空间</div>
          <nav aria-label="中央经营导航">{navigation}</nav>
          <div className="sidebar-note">
            当前成员的操作范围
            <br />
            由业务权限实时核验
          </div>
        </Layout.Sider>
      )}
      <Layout>
        <Layout.Header className="topbar">
          {compact && !collaboration && (
            <Button onClick={() => setOpen(true)} aria-label="打开经营导航">
              菜单
            </Button>
          )}
          <div className="workspace-location">
            <span>{collaboration ? "门店协作" : active?.label}</span>
            <strong>
              {collaboration
                ? "商品协作"
                : active?.pages.find(
                    ([path]) => path === location.pathname,
                  )?.[1]}
            </strong>
          </div>
          {!collaboration && (
            <WorkspaceSearch
              label="搜索中央功能"
              groups={centralGroups.map((g) => ({
                label: g.label,
                pages: g.pages.map(
                  ([target, label]) => [target, label] as const,
                ),
              }))}
              onNavigate={(path) => location.assign(target(path))}
            />
          )}
          <Space className="account-context">
            <span className="environment-badge">企业身份</span>
            <Avatar size="small">{subject.slice(0, 1).toUpperCase()}</Avatar>
            <span className="actor-name" title={subject}>
              {subject}
            </span>
          </Space>
        </Layout.Header>
        <Layout.Content className="workspace">
          <div className="organization-context">
            <span>当前组织</span>
            <Typography.Text type="secondary" copyable={{ text: tenant }}>
              <span title={tenant}>{tenant}</span>
            </Typography.Text>
          </div>
          {children}
        </Layout.Content>
        <Layout.Footer className="footer">
          统一电商业务平台<span>企业经营 · 按授权范围操作</span>
        </Layout.Footer>
      </Layout>
      <RecordModal
        title="经营导航"
        expandable={false}
        footer={null}
        width={480}
        open={open}
        onCancel={() => setOpen(false)}
      >
        {navigation}
      </RecordModal>
    </Layout>
  );
}
