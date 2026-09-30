import { Alert, Card, Empty } from "antd";
import { useId, useState } from "react";
import {
  JourneyNodeKind,
  type Journey,
  type JourneyNode,
} from "../shared/contracts";
import { Detail } from "../shared/ui";
import { Icon, type IconName } from "../shared/Icon";

const nodeKinds: Record<
  JourneyNode["kind"],
  { label: string; icon: IconName }
> = {
  WAIT: { label: "等待", icon: "trade" },
  DECIDE: { label: "规则分支", icon: "platform" },
  GRANT: { label: "授予权益", icon: "bag" },
  COUPON: { label: "发放受控券", icon: "marketing" },
  NOTIFY: { label: "站内触达", icon: "member" },
  END: { label: "结束", icon: "overview" },
};
type Edge = { from: string; to: string; label: string };
const outgoing = (node: JourneyNode): Edge[] =>
  node.kind === JourneyNodeKind.DECIDE
    ? [
        { from: node.id, to: node.yesNext ?? "", label: "命中" },
        { from: node.id, to: node.noNext ?? "", label: "未命中" },
      ].filter((edge) => edge.to)
    : node.next
      ? [{ from: node.id, to: node.next, label: "" }]
      : [];

/** 最长拓扑层只决定读图位置，不重排或修复权威配置；无法分层的节点仍保留。 */
function arrange(nodes: JourneyNode[]) {
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const allEdges = nodes.flatMap(outgoing);
  const edges = allEdges.filter((edge) => byId.has(edge.to));
  const inbound = new Map(nodes.map((node) => [node.id, 0]));
  for (const edge of edges) inbound.set(edge.to, inbound.get(edge.to)! + 1);
  const queue = nodes
    .filter((node) => inbound.get(node.id) === 0)
    .map((node) => node.id);
  const ranks = new Map<string, number>();
  for (let cursor = 0; cursor < queue.length; cursor++) {
    const id = queue[cursor];
    if (!ranks.has(id)) ranks.set(id, 0);
    for (const edge of edges.filter((edge) => edge.from === id)) {
      ranks.set(edge.to, Math.max(ranks.get(edge.to) ?? 0, ranks.get(id)! + 1));
      inbound.set(edge.to, inbound.get(edge.to)! - 1);
      if (inbound.get(edge.to) === 0) queue.push(edge.to);
    }
  }
  const unresolved = nodes.filter((node) => !queue.includes(node.id));
  const finalRank = Math.max(0, ...ranks.values()) + 1;
  for (const node of unresolved) ranks.set(node.id, finalRank);
  const layers = new Map<number, JourneyNode[]>();
  for (const node of nodes) {
    const rank = ranks.get(node.id) ?? 0;
    layers.set(rank, [...(layers.get(rank) ?? []), node]);
  }
  const width = Math.max(
    260,
    ...[...layers.values()].map((layer) => layer.length * 212 + 24),
  );
  const positioned = [...layers.entries()]
    .sort(([a], [b]) => a - b)
    .flatMap(([rank, layer]) =>
      layer.map((node, index) => ({
        node,
        x: (width - layer.length * 212) / 2 + index * 212 + 12,
        y: rank * 162 + 24,
      })),
    );
  return {
    positioned,
    edges,
    width,
    height: (Math.max(0, ...layers.keys()) + 1) * 162 + 16,
    missing: allEdges.length - edges.length,
    unresolved: unresolved.length,
  };
}
function caption(node: JourneyNode) {
  if (node.kind === JourneyNodeKind.WAIT)
    return `${node.seconds ?? "未配置"} 秒`;
  if (node.kind === JourneyNodeKind.GRANT)
    return node.benefit
      ? `${node.benefit.benefitId} · v${node.benefit.version}`
      : "未配置权益";
  if (node.kind === JourneyNodeKind.COUPON)
    return node.coupon
      ? `${node.coupon.definitionId} · v${node.coupon.version}`
      : "未配置券";
  if (node.kind === JourneyNodeKind.NOTIFY) return node.title || "未配置标题";
  if (node.kind === JourneyNodeKind.DECIDE) return "按规则结果分流";
  return "本路径结束";
}

/** 关系图仅展示已读取版本，点击节点可核对事实，不预测命中或业务副作用。 */
export function JourneyMap({ journey }: { journey: Journey }) {
  const [selected, setSelected] = useState(journey.entry);
  const marker = useId();
  const layout = arrange(journey.nodes);
  const current =
    journey.nodes.find((node) => node.id === selected) ?? journey.nodes[0];
  const points = new Map(
    layout.positioned.map((point) => [point.node.id, point]),
  );
  if (!current)
    return (
      <Empty
        description="此版本没有执行节点"
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  return (
    <section className="journey-map" aria-label="旅程版本关系图">
      <div className="journey-map-heading">
        <strong>看清每一个分支</strong>
        <span>版本配置 · 非执行记录</span>
      </div>
      <p className="journey-map-help">
        选择节点查看配置；连接表示下一节点，分支按实际规则结果执行。
      </p>
      {(layout.missing > 0 || layout.unresolved > 0) && (
        <Alert type="warning" title="部分连接或节点无法分层，请核对版本配置" />
      )}
      <div className="journey-map-content">
        <div
          className="journey-map-scroll"
          tabIndex={0}
          aria-label="关系图滚动区域"
        >
          <div
            className="journey-board"
            style={{ width: layout.width, height: layout.height }}
          >
            <svg width={layout.width} height={layout.height} aria-hidden="true">
              <defs>
                <marker
                  id={marker}
                  markerWidth="7"
                  markerHeight="7"
                  refX="6"
                  refY="3.5"
                  orient="auto"
                >
                  <path d="M0 0L7 3.5L0 7" fill="currentColor" />
                </marker>
              </defs>
              {layout.edges.map((edge, index) => {
                const start = points.get(edge.from)!;
                const end = points.get(edge.to)!;
                const x1 = start.x + 94,
                  y1 = start.y + 108,
                  x2 = end.x + 94,
                  y2 = end.y;
                const middle = (y1 + y2) / 2;
                const active =
                  edge.from === current.id || edge.to === current.id;
                return (
                  <g
                    key={index}
                    className={
                      active ? "journey-edge is-active" : "journey-edge"
                    }
                    data-from={edge.from}
                    data-to={edge.to}
                  >
                    <path
                      d={`M${x1} ${y1} C${x1} ${middle},${x2} ${middle},${x2} ${y2 - 4}`}
                      fill="none"
                      markerEnd={`url(#${marker})`}
                    />
                    {edge.label && (
                      <text
                        x={(x1 + x2) / 2 + (edge.label === "命中" ? -12 : 12)}
                        y={middle - 6}
                        textAnchor={edge.label === "命中" ? "end" : "start"}
                      >
                        {edge.label}
                      </text>
                    )}
                  </g>
                );
              })}
            </svg>
            {layout.positioned.map(({ node, x, y }) => (
              <button
                type="button"
                key={node.id}
                className={`journey-map-node${current.id === node.id ? " is-selected" : ""}${node.kind === JourneyNodeKind.END ? " is-end" : ""}`}
                style={{ left: x, top: y }}
                onClick={() => setSelected(node.id)}
                aria-pressed={current.id === node.id}
                aria-label={`${nodeKinds[node.kind].label} ${node.id}${journey.entry === node.id ? "（入口）" : ""}，${
                  outgoing(node)
                    .map((edge) => `${edge.label || "下一步"}：${edge.to}`)
                    .join("，") || "本路径结束"
                }`}
              >
                <span className="journey-node-top">
                  <Icon name={nodeKinds[node.kind].icon} />
                  <span>{nodeKinds[node.kind].label}</span>
                  {journey.entry === node.id && <small>入口</small>}
                </span>
                <strong>{node.id}</strong>
                <span className="journey-node-caption" title={caption(node)}>
                  {caption(node)}
                </span>
              </button>
            ))}
          </div>
        </div>
        <Card
          className="journey-node-detail"
          title={`${current.id} · ${nodeKinds[current.kind].label}`}
          size="small"
        >
          <Detail value={current} />
        </Card>
      </div>
    </section>
  );
}
