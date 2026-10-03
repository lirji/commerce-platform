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
const buttonPalette = {
  primary: "#365C83",
  hover: "#294A6B",
  active: "#223F5B",
  text: "#334155",
  border: "#CBD5E1",
  hoverBorder: "#9FAFBE",
  hoverBg: "#F4F7FA",
  selectedBg: "#E9F0F6",
  disabledText: "#748094",
  disabledBorder: "#DCE2E8",
  disabledBg: "#F4F6F8",
};
for (const [name, value] of Object.entries(buttonPalette))
  document.documentElement.style.setProperty(`--button-${name}`, value);
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
    controlHeight: 32,
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
    // 页面、行操作和弹层共用紧凑尺寸，保留可读字号，避免按钮挤占业务内容。
    Button: {
      controlHeight: 32,
      controlHeightSM: 32,
      controlHeightLG: 32,
      contentFontSize: 14,
      contentFontSizeSM: 14,
      contentFontSizeLG: 14,
      paddingInline: 10,
      paddingInlineSM: 10,
      paddingInlineLG: 10,
      borderRadius: 6,
      borderRadiusSM: 6,
      borderRadiusLG: 6,
      primaryShadow: "none",
      defaultShadow: "none",
      colorPrimary: buttonPalette.primary,
      colorPrimaryHover: buttonPalette.hover,
      colorPrimaryActive: buttonPalette.active,
      colorLink: buttonPalette.primary,
      colorLinkHover: buttonPalette.hover,
      colorLinkActive: buttonPalette.active,
      primaryColor: palette.surface,
      defaultColor: buttonPalette.text,
      defaultBorderColor: buttonPalette.border,
      defaultHoverColor: buttonPalette.text,
      defaultHoverBorderColor: buttonPalette.hoverBorder,
      defaultHoverBg: buttonPalette.hoverBg,
      defaultActiveColor: buttonPalette.primary,
      defaultActiveBorderColor: buttonPalette.primary,
      defaultActiveBg: buttonPalette.selectedBg,
      colorTextDisabled: buttonPalette.disabledText,
      borderColorDisabled: buttonPalette.disabledBorder,
      colorBgContainerDisabled: buttonPalette.disabledBg,
      textHoverBg: buttonPalette.hoverBg,
    },
    Table: {
      headerBg: palette.raised,
      rowHoverBg: palette.raised,
      headerColor: palette.ink,
      cellPaddingBlock: 10,
      cellPaddingInline: 12,
      cellPaddingBlockMD: 10,
      cellPaddingInlineMD: 12,
    },
    Card: {
      headerFontSize: 15,
      borderRadiusLG: 12,
      headerHeight: 48,
      bodyPadding: 16,
    },
    Modal: { borderRadiusLG: 12, paddingContentHorizontalLG: 20 },
    Tabs: {
      itemSelectedColor: palette.accent,
      itemHoverColor: palette.ink,
      inkBarColor: palette.accent,
    },
  },
};
export const memberTheme = consoleTheme;
