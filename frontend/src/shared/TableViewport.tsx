import { Typography } from "antd";
import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

type ScrollMemory = {
  position: number;
  save: (position: number) => void;
};

/** 宽表提供一个键盘停靠点。滚动目标取决于 Ant 表头是否独立渲染。 */
export function TableViewport({
  children,
  inPanel,
  hidden,
  memory,
}: {
  children: ReactNode;
  inPanel?: boolean;
  hidden?: boolean;
  memory?: ScrollMemory;
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLElement | null>(null);
  const [overflowing, setOverflowing] = useState(false);
  const hintId = useId();
  useLayoutEffect(() => {
    const root = viewport.current;
    if (!root || hidden) return;
    let observed: HTMLElement | null = null;
    const measure = () => {
      // 详情隐藏列表时没有可测宽度。保留提示，返回时列表高度不会先缩再涨。
      if (!root.clientWidth) return;
      const inner = root.querySelector<HTMLElement>(
        ".ant-table-content, .ant-table-body",
      );
      if (inner !== observed) {
        if (observed) resize.unobserve(observed);
        if (inner) resize.observe(inner);
        observed = inner;
      }
      const target =
        [root, inner].find(
          (node) =>
            node &&
            node.clientWidth > 0 &&
            node.scrollWidth > node.clientWidth + 1,
        ) ?? null;
      if (memory && target) {
        const maximum = target.scrollWidth - target.clientWidth;
        target.scrollLeft = Math.min(memory.position, maximum);
      }
      scroller.current = target;
      setOverflowing(!!target);
    };
    const resize = new ResizeObserver(measure);
    resize.observe(root);
    const mutations = new MutationObserver(measure);
    mutations.observe(root, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    measure();
    return () => {
      resize.disconnect();
      mutations.disconnect();
      scroller.current = null;
    };
  }, [hidden, memory]);
  const remember = (node: HTMLElement) => {
    if (!memory) return;
    const { scrollLeft, scrollWidth, clientWidth } = node;
    // 宽屏或隐藏列导致位置暂时被约束时，保留原来的逻辑位置。
    if (scrollLeft === Math.min(memory.position, scrollWidth - clientWidth))
      return;
    memory.position = scrollLeft;
    memory.save(scrollLeft);
  };
  return (
    <div hidden={hidden}>
      <div
        ref={viewport}
        className={inPanel ? "list-table table-viewport" : "table-viewport"}
        role={overflowing ? "region" : undefined}
        aria-label={overflowing ? "列表完整字段" : undefined}
        aria-describedby={overflowing ? hintId : undefined}
        tabIndex={overflowing ? 0 : undefined}
        onScrollCapture={(event) => {
          if (scroller.current && event.target === scroller.current)
            remember(scroller.current);
        }}
        onKeyDown={(event) => {
          const node = scroller.current;
          if (
            event.target !== event.currentTarget ||
            !node ||
            event.altKey ||
            event.ctrlKey ||
            event.metaKey ||
            event.shiftKey
          )
            return;
          switch (event.key) {
            case "ArrowLeft":
            case "ArrowRight":
              node.scrollLeft += event.key === "ArrowRight" ? 120 : -120;
              break;
            case "Home":
              node.scrollLeft = 0;
              break;
            case "End":
              node.scrollLeft = node.scrollWidth;
              break;
            default:
              return;
          }
          remember(node);
          event.preventDefault();
        }}
      >
        {children}
      </div>
      {overflowing && (
        <Typography.Text
          id={hintId}
          type="secondary"
          className="table-overflow-hint"
        >
          左右滑动查看完整字段。键盘聚焦表格后可用方向键、Home 或 End。
        </Typography.Text>
      )}
    </div>
  );
}
