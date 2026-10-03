import { theme, type ThemeConfig } from "antd";

/** 全站共用统一架构浅色 token，经营台与会员端不再各走一套色板。 */
export const palette = {
  canvas: "#F6F8FB",
  surface: "#FFFFFF",
  raised: "#F8FAFC",
  ink: "#172238",
  muted: "#5B677B",
  line: "#E3E8F0",
  accent: "#3455DB",
  hover: "#2944B4",
  focus: "#3455DB",
  selected: "#EEF2FF",
  ok: "#18704F",
  okSoft: "#EDF8F2",
  warn: "#8B5415",
  warnSoft: "#FFF6E8",
  error: "#B23B43",
  errorSoft: "#FFF0F1",
  pending: "#5C49AF",
  pendingSoft: "#F3F0FC",
  idle: "#5B677B",
  idleSoft: "#F0F3F7",
};
export const memberPalette = palette;
// 经营框架与会员页面共用组件体系，但导航/画布只在 B 端作用域使用。
export const workspacePalette = {
  canvas: "#F3F5F2",
  spine: "#18313D",
  spineHover: "#254450",
  spineText: "#C3D1D8",
  spineActive: "#F3F6F8",
  signal: "#BDEACF",
};
for (const [name, value] of Object.entries(workspacePalette))
  document.documentElement.style.setProperty(`--workspace-${name}`, value);
for (const [name, value] of Object.entries(palette))
  document.documentElement.style.setProperty(`--${name}`, value);
const fontFamily =
  '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif';
function tokens(radius: number): ThemeConfig["token"] {
  return {
    motion: false,
    colorPrimary: palette.accent,
    colorPrimaryHover: palette.hover,
    colorSuccess: palette.ok,
    colorWarning: palette.warn,
    colorError: palette.error,
    colorInfo: palette.pending,
    colorText: palette.ink,
    colorTextSecondary: palette.muted,
    colorTextTertiary: palette.muted,
    colorBorder: palette.line,
    colorBorderSecondary: palette.line,
    colorBgLayout: palette.canvas,
    colorBgContainer: palette.surface,
    colorBgElevated: palette.surface,
    colorLink: palette.focus,
    colorLinkHover: palette.hover,
    borderRadius: radius,
    controlHeight: 38,
    fontSize: 14,
    fontFamily,
  };
}
export const consoleTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: tokens(8),
  components: {
    Layout: {
      siderBg: palette.surface,
      headerBg: palette.surface,
      bodyBg: palette.canvas,
      footerBg: palette.surface,
    },
    Menu: {
      itemBg: palette.surface,
      itemSelectedBg: palette.selected,
      itemSelectedColor: palette.accent,
      itemHoverBg: palette.raised,
      groupTitleColor: palette.muted,
    },
    // 即使组件内部使用 small/large，操作按钮仍遵守同一尺寸，避免页面再次分化。
    Button: {
      controlHeight: 38,
      controlHeightSM: 38,
      controlHeightLG: 38,
      contentFontSize: 14,
      contentFontSizeSM: 14,
      contentFontSizeLG: 14,
      paddingInline: 14,
      paddingInlineSM: 14,
      paddingInlineLG: 14,
      borderRadius: 8,
      borderRadiusSM: 8,
      borderRadiusLG: 8,
      primaryShadow: "none",
      colorPrimary: palette.accent,
      colorPrimaryHover: palette.hover,
      primaryColor: palette.surface,
      defaultHoverColor: palette.accent,
      defaultHoverBorderColor: palette.accent,
    },
    Table: {
      headerBg: palette.raised,
      rowHoverBg: palette.raised,
      headerColor: palette.muted,
    },
    Card: { headerFontSize: 15, borderRadiusLG: 12, headerHeight: 56 },
    Modal: { borderRadiusLG: 12, paddingContentHorizontalLG: 20 },
    Tabs: {
      itemSelectedColor: palette.accent,
      itemHoverColor: palette.ink,
      inkBarColor: palette.accent,
    },
  },
};
export const memberTheme = consoleTheme;
