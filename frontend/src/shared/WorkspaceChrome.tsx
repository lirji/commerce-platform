import { Select, type RefSelectProps } from "antd";
import { useEffect, useRef } from "react";

export function WorkspaceBrand() {
  return (
    <div className="brand workspace-brand">
      <span className="brand-mark">
        <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <path
            d="M5 5h14v14H5zM13 13h14v14H13z"
            stroke="currentColor"
            strokeWidth="1.7"
          />
          <path d="M13 19h6v-6" stroke="currentColor" strokeWidth="1.7" />
        </svg>
      </span>
      <span>
        日常经营<span className="brand-sub">COMMERCE / OPERATIONS</span>
      </span>
    </div>
  );
}

type SearchGroup = {
  label: string;
  pages: readonly (readonly [string, string])[];
};

/** 跳过导航只移动焦点，不改写 hash，以免丢失列表筛选和游标。 */
export function WorkspaceSkipLink() {
  return (
    <a
      className="workspace-skip-link"
      href="#workspace-content"
      onClick={(event) => {
        const content = document.getElementById("workspace-content");
        if (!content) return;
        event.preventDefault();
        content.focus({ preventScroll: true });
        content.scrollIntoView({ block: "start" });
      }}
    >
      跳到业务内容
    </a>
  );
}

/** 快捷键只聚焦原有导航搜索；中央仍使用原生跳转和浏览器的离开保护。 */
export function WorkspaceSearch({
  groups,
  label,
  onNavigate,
}: {
  groups: SearchGroup[];
  label: string;
  onNavigate: (target: string) => void;
}) {
  const select = useRef<RefSelectProps>(null);
  useEffect(() => {
    const focus = (event: KeyboardEvent) => {
      if (
        (event.metaKey || event.ctrlKey) &&
        event.key.toLowerCase() === "k" &&
        !event.altKey &&
        !event.isComposing
      ) {
        // 弹层拥有自己的焦点范围，不能通过快捷键把焦点带到被遮挡的壳层。
        if (
          Array.from(document.querySelectorAll('[role="dialog"]')).some(
            (dialog) => dialog.getClientRects().length > 0,
          )
        )
          return;
        event.preventDefault();
        select.current?.focus();
      }
    };
    addEventListener("keydown", focus);
    return () => removeEventListener("keydown", focus);
  }, []);
  return (
    <div className="workspace-search">
      <Select<string | null>
        ref={select}
        className="nav-search"
        aria-label={label}
        showSearch={{ optionFilterProp: "label" }}
        placeholder="查找经营功能"
        value={null}
        options={groups.map((group) => ({
          label: group.label,
          options: group.pages.map(([value, name]) => ({ value, label: name })),
        }))}
        onChange={(value) => {
          if (value) onNavigate(value);
        }}
        popupMatchSelectWidth={300}
      />
      <kbd aria-hidden="true">⌘ / Ctrl K</kbd>
    </div>
  );
}
