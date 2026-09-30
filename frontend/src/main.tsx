import React, { lazy, Suspense } from "react";
import { centralRoutes } from "./iam/navigation";
import ReactDOM from "react-dom/client";
import { ConfigProvider, App as AntApp, Spin } from "antd";
import zhCN from "antd/locale/zh_CN";
const CentralProducts = lazy(() =>
  import("./iam/CentralProducts").then((module) => ({
    default: module.CentralProducts,
  })),
);
import { App } from "./app/App";
import "./style.css";
import "./workspace.css";
import { consoleTheme } from "./theme";
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      form={{
        requiredMark: (label, { required }) => (
          <>
            {required && <span className="required-mark" aria-hidden="true" />}
            {label}
          </>
        ),
      }}
      theme={consoleTheme}
    >
      <AntApp>
        <Suspense
          fallback={
            <div className="page-loading">
              <Spin description="正在加载经营工作台" />
            </div>
          }
        >
          {[...centralRoutes, "/iam/callback"].includes(location.pathname) ? (
            <CentralProducts />
          ) : (
            <App />
          )}
        </Suspense>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
