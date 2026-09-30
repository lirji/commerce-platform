import React from "react";
import ReactDOM from "react-dom/client";
import { ConfigProvider, App as AntApp } from "antd";
import zhCN from "antd/locale/zh_CN";
import { CentralProducts } from "./iam/CentralProducts";
import { App } from "./app/App";
import "./style.css";
import { consoleTheme } from "./theme";
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      theme={consoleTheme}
    >
      <AntApp>
        {[
          "/operations/products",
          "/operations/coupon-definitions",
          "/operations/point-offers",
          "/operations/member-points",
          "/operations/member-cycles",
          "/operations/member-cycle-benefits",
          "/operations/member-behavior",
          "/operations/member-tags",
          "/operations/member-growth",
          "/operations/members",
          "/operations/directory",
          "/operations/inventory",
          "/operations/catalog",
          "/collaboration/products",
          "/iam/callback",
        ].includes(location.pathname) ? (
          <CentralProducts />
        ) : (
          <App />
        )}
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
