import { theme, type ThemeConfig } from "antd";

/** 自有布局与 Ant 组件共享同一套深色语义，避免弹窗和工作台出现两种主题。 */
export const palette = {
  canvas: "#0B1020", surface: "#121B2E", raised: "#19243A", ink: "#EEF2FF",
  muted: "#A8B6CF", line: "#34425B", accent: "#315CDA", hover: "#264CBF",
  focus: "#9CB7FF", ok: "#047857", warn: "#A34D08", error: "#B91C1C",
  pending: "#5742BA", idle: "#43516B",
};
for (const [name, value] of Object.entries(palette)) document.documentElement.style.setProperty(`--${name}`, value);
export const consoleTheme: ThemeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    motion: false, colorPrimary: palette.accent, colorPrimaryHover: palette.hover,
    colorSuccess: palette.ok, colorWarning: palette.warn, colorError: palette.error, colorInfo: palette.pending,
    colorText: palette.ink, colorTextSecondary: palette.muted, colorTextTertiary: palette.muted,
    colorBorder: palette.line, colorBorderSecondary: palette.line, colorBgLayout: palette.canvas,
    colorBgContainer: palette.surface, colorBgElevated: palette.raised, colorLink: palette.focus,
    colorLinkHover: palette.ink, borderRadius: 10, controlHeight: 36,
    fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  components: {
    Layout: { siderBg: palette.surface, headerBg: palette.surface },
    Menu: { itemBg: palette.surface, itemSelectedBg: palette.accent, itemSelectedColor: "#FFFFFF", itemHoverBg: palette.raised, groupTitleColor: palette.muted },
    Button: { primaryShadow: "none", colorPrimary: palette.accent, colorPrimaryHover: palette.hover, primaryColor: "#FFFFFF", defaultHoverColor: palette.focus, defaultHoverBorderColor: palette.focus },
    Table: { headerBg: palette.raised, rowHoverBg: palette.raised, headerColor: palette.muted },
    Card: { headerFontSize: 16 },
    Tabs: { itemSelectedColor: palette.focus, itemHoverColor: palette.ink, inkBarColor: palette.focus },
  },
};
