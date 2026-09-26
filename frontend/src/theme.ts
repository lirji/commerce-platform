import { theme, type ThemeConfig } from "antd";

/** 全站共用统一架构浅色 token，经营台与会员端不再各走一套色板。 */
export const palette = {
  canvas: "#EEF1F5", surface: "#FFFFFF", raised: "#F8FAFC", ink: "#111827",
  muted: "#4B5563", line: "#CBD5E1", accent: "#1D4ED8", hover: "#1E40AF",
  focus: "#1D4ED8", ok: "#047857", warn: "#B45309", error: "#B91C1C",
  pending: "#4338CA", idle: "#374151",
};
export const memberPalette = palette;
for (const [name, value] of Object.entries(palette)) document.documentElement.style.setProperty(`--${name}`, value);
const fontFamily = '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif';
function tokens(radius: number): ThemeConfig["token"] {
  return {
    motion: false, colorPrimary: palette.accent, colorPrimaryHover: palette.hover,
    colorSuccess: palette.ok, colorWarning: palette.warn, colorError: palette.error, colorInfo: palette.pending,
    colorText: palette.ink, colorTextSecondary: palette.muted, colorTextTertiary: palette.muted,
    colorBorder: palette.line, colorBorderSecondary: palette.line, colorBgLayout: palette.canvas,
    colorBgContainer: palette.surface, colorBgElevated: palette.raised, colorLink: palette.focus,
    colorLinkHover: palette.hover, borderRadius: radius, controlHeight: 36, fontFamily,
  };
}
export const consoleTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: tokens(6),
  components: {
    Layout: { siderBg: palette.surface, headerBg: palette.surface, bodyBg: palette.canvas, footerBg: palette.surface },
    Menu: { itemBg: palette.surface, itemSelectedBg: "#DBEAFE", itemSelectedColor: palette.accent, itemHoverBg: palette.raised, groupTitleColor: palette.muted },
    Button: { primaryShadow: "none", colorPrimary: palette.accent, colorPrimaryHover: palette.hover, primaryColor: "#FFFFFF", defaultHoverColor: palette.accent, defaultHoverBorderColor: palette.accent },
    Table: { headerBg: palette.raised, rowHoverBg: "#F8FAFC", headerColor: palette.muted },
    Card: { headerFontSize: 16 },
    Tabs: { itemSelectedColor: palette.accent, itemHoverColor: palette.ink, inkBarColor: palette.accent },
  },
};
export const memberTheme = consoleTheme;
