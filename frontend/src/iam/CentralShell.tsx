import {
  Avatar,
  Button,
  Drawer,
  Grid,
  Layout,
  Menu,
  Select,
  Space,
} from "antd";
import { useState, type ReactNode } from "react";
import { Icon, type IconName } from "../shared/Icon";
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
          <div className="brand">
            <span className="brand-mark">
              <Icon name="overview" />
            </span>
            <span>
              日常经营<span className="brand-sub">COMMERCE WORKSPACE</span>
            </span>
          </div>
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
            <Select
              className="nav-search"
              aria-label="搜索中央功能"
              showSearch={{ optionFilterProp: "label" }}
              value={null}
              placeholder="查找经营功能…"
              options={centralGroups.flatMap((g) =>
                g.pages.map(([value, label]) => ({ value, label })),
              )}
              onChange={(path) => {
                if (path) location.assign(target(path));
              }}
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
            <span title={tenant}>{tenant}</span>
          </div>
          {children}
        </Layout.Content>
        <Layout.Footer className="footer">
          统一电商业务平台<span>企业经营 · 按授权范围操作</span>
        </Layout.Footer>
      </Layout>
      <Drawer
        title="经营导航"
        placement="left"
        size={280}
        open={open}
        onClose={() => setOpen(false)}
      >
        {navigation}
      </Drawer>
    </Layout>
  );
}
