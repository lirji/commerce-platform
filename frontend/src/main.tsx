import React from "react";
import ReactDOM from "react-dom/client";
import { ConfigProvider, App as AntApp } from "antd";
import zhCN from "antd/locale/zh_CN";
import { App } from "./app/App";
import "./style.css";
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      theme={{
        token: {
          // 运营界面关闭离场动画，避免透明图标残留在可访问名称中。
          motion: false,
          colorPrimary: "#1D4ED8",
          colorSuccess: "#047857",
          colorWarning: "#B45309",
          colorError: "#B91C1C",
          colorInfo: "#4338CA",
          colorText: "#111827",
          colorTextSecondary: "#4B5563",
          colorBorder: "#CBD5E1",
          colorBgLayout: "#EEF1F5",
          borderRadius: 6,
          controlHeight: 32,
          fontFamily:
            'Inter, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif',
        },
        components: {
          Menu: { itemSelectedBg: "#1D4ED8", itemSelectedColor: "#FFFFFF" },
          Button: { primaryShadow: "none" },
          Table: { headerBg: "#F7F9FC" },
        },
      }}
    >
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
