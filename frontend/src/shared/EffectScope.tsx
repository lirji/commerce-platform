import { Descriptions, Grid } from "antd";

export const effectTimeBasis = {
  campaigns: "下单时间",
  journeys: "会员入组时间",
  deliveries: "发券批次创建时间",
  counts: "旅程执行发生时间",
};

export function EffectScope({
  store,
  from,
  to,
  basis,
}: {
  store: string;
  from: string;
  to: string;
  basis: string;
}) {
  const compact = Grid.useBreakpoint().md === false;
  return (
    <Descriptions
      size="small"
      column={compact ? 1 : 3}
      className="effect-scope"
      items={[
        { key: "store", label: "已查询门店", children: store },
        { key: "basis", label: "时间依据", children: basis },
        {
          key: "bounds",
          label: "实际窗口（UTC，含起点）",
          children: (
            <span>
              {from} 至 {to}（不含）
            </span>
          ),
        },
      ]}
    />
  );
}
