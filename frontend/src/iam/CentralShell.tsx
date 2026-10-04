import { RecordModal } from "../shared/interactions";
import { Avatar, Button, Grid, Layout, Menu, Space, Typography } from "antd";
import { useState, type ReactNode } from "react";
import { Icon, type IconName } from "../shared/Icon";
import {
  WorkspaceBrand,
  WorkspaceSearch,
  WorkspaceSkipLink,
} from "../shared/WorkspaceChrome";
import type { MenuProps } from "antd";
import type { NavigationItem, NavigationModel } from "./businessNavigation";

/** 壳层仅承载导航和视觉，原页面继续负责独立权限、凭据及未保存/未知结果保护。 */
export function CentralShell({
  tenant,
  subject,
  navigation: model,
  children,
}: {
  tenant: string;
  subject: string;
  navigation: NavigationModel;
  children: ReactNode;
}) {
  const screens = Grid.useBreakpoint();
  const compact = screens.md === false;
  const [open, setOpen] = useState(false);
  const collaboration = location.pathname.startsWith("/collaboration/");
  const active = model.groups.find((g) =>
    g.pages.some(([path]) => path === location.pathname),
  );
  const target = (path: string) => {
    const query = new URLSearchParams({ tenant_id: tenant });
    const environment = new URLSearchParams(location.search).get("environment");
    if (environment) query.set("environment", environment);
    return path + "?" + query;
  };
  const item = (
    value: NavigationItem,
  ): NonNullable<MenuProps["items"]>[number] => ({
    key: value.route ?? value.code,
    label: value.route ? (
      <a href={target(value.route)}>{value.label}</a>
    ) : (
      value.label
    ),
    icon: value.code.startsWith("group.") ? (
      <Icon name={value.code.slice(6) as IconName} />
    ) : undefined,
    children: value.children.length ? value.children.map(item) : undefined,
  });
  const navigation = (
    <Menu
      key={model.items.map((value) => value.code).join("|")}
      mode="inline"
      selectedKeys={[location.pathname]}
      defaultOpenKeys={active ? [active.key] : []}
      items={model.items.map(item)}
    />
  );
  return (
    <Layout className="app admin-app central-app">
      <WorkspaceSkipLink />
      {!compact && (
        <Layout.Sider width={236} theme="light" className="sidebar">
          <WorkspaceBrand />
          <div className="nav-caption">企业经营工作空间</div>
          <nav aria-label="中央经营导航">{navigation}</nav>
          <div className="sidebar-note">
            当前成员可用页面
            <br />
            按本次权限结果显示
          </div>
        </Layout.Sider>
      )}
      <Layout>
        <Layout.Header className="topbar">
          {compact && (
            <Button onClick={() => setOpen(true)} aria-label="打开经营导航">
              菜单
            </Button>
          )}
          <div className="workspace-location">
            <span>{collaboration ? "门店协作" : active?.label}</span>
            <strong>
              {model.routes.get(location.pathname) ?? "授权工作区"}
            </strong>
          </div>
          {model.routes.size > 0 && (
            <WorkspaceSearch
              label="搜索中央功能"
              groups={model.groups.map((g) => ({
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
        <Layout.Content
          className="workspace"
          id="workspace-content"
          role="main"
          tabIndex={-1}
          aria-label="业务内容"
        >
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
        width={400}
        open={open}
        onCancel={() => setOpen(false)}
      >
        {navigation}
      </RecordModal>
    </Layout>
  );
}
