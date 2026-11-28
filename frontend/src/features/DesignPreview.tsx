import { Button, Segmented } from "antd";
import { useRouteState } from "../shared/routeState";

const designs = {
  minimal: "现代简约",
  operations: "高密度运营",
  analytics: "数据驱动",
} as const;

type Design = keyof typeof designs;

export function useDesignPreview(enabled = true) {
  const [value, setDesign] = useRouteState("design", "");
  const design =
    enabled && Object.hasOwn(designs, value) ? (value as Design) : undefined;
  return {
    design,
    open: () => setDesign("minimal"),
    close: () => setDesign(""),
    select: (next: string) => setDesign(next),
  };
}

export function DesignPreviewBar({
  design,
  close,
  select,
}: ReturnType<typeof useDesignPreview>) {
  if (!design) return null;
  return (
    <div className="design-preview-switch">
      <span>设计预览</span>
      <Segmented
        aria-label="设计方案"
        options={Object.entries(designs).map(([value, label]) => ({
          label,
          value,
        }))}
        value={design}
        onChange={select}
      />
      <Button type="text" onClick={close}>
        退出预览
      </Button>
    </div>
  );
}
