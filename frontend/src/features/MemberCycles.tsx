import { Alert, Button, Card, Col, Descriptions, Input, InputNumber, Row, Space, Statistic, Table } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ActionButton, CommandModal, ErrorNotice, instant, time } from "../shared/ui";

type Cycle = { memberId: string; enabled: boolean; policyVersion: number; cycleStart: string | null; cycleEnd: string | null; currentGrowth: number; retentionGrowth: number; memberLevel: string; version: number };
type Policy = { version: number; effectiveFrom: string; periodDays: number; levels: { code: string; minimumGrowth: number }[] };
type Bundle = { bindingId: string; policyVersion: number; level: string; storeId: string; validUntil: string; benefits: { benefitId: string; version: number }[] };

/** 周期与权益只展示后端考核事实，未配置时不以累计成长伪造保级进度。 */
export function MemberCycles({ admin }: { admin: boolean }) {
  const [member, setMember] = useState("");
  const [policyVersion, setPolicyVersion] = useState(1);
  const [after, setAfter] = useState(0);
  const cycle = useResource<Cycle>(admin ? member ? `/admin/member-cycles/${encode(member)}` : null : "/members/me/cycle");
  const policies = useResource<Policy[]>(admin ? `/admin/member-cycles/policies?after=${after}` : null);
  const bundles = useResource<Bundle[]>(admin ? `/admin/member-cycle-benefits?policyVersion=${policyVersion}` : null);
  return <div className="cycle-workspace">
    {admin && <Input.Search aria-label="查询会员周期" placeholder="会员标识" enterButton="查看周期" onSearch={v => setMember(v.trim())} style={{ maxWidth: 440 }} />}
    <ErrorNotice error={cycle.error} />
    {cycle.data && <>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="当前等级" value={cycle.data.memberLevel} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="本周期净成长" value={cycle.data.currentGrowth} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="上一周期保级成长" value={cycle.data.retentionGrowth} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="考核策略版本" value={cycle.data.policyVersion || "未启用"} /></Card></Col>
      </Row>
      <Card title="周期考核">
        {!cycle.data.enabled ? <Alert type="info" title="尚未启用周期策略或尚未完成首次考核" description="当前仍展示已保存的会员等级。运营配置并完成考核后显示周期。" /> : <>
          {cycle.data.cycleEnd && Date.parse(cycle.data.cycleEnd) <= Date.now() && <Alert type="warning" title="当前快照已到周期边界，等待后台考核更新" />}
          <Descriptions items={[{ key: "start", label: "周期开始", children: time(cycle.data.cycleStart) }, { key: "end", label: "周期结束", children: time(cycle.data.cycleEnd) }, { key: "version", label: "考核快照版本", children: cycle.data.version }]} />
          <p className="muted">本周期达标升级，上一周期净成长用于保级；退款归回原订单周期。已授予的权益按原有效期享有。</p>
        </>}
        <Space wrap className="section-actions">
          <Button onClick={cycle.refresh} loading={cycle.loading}>刷新周期</Button>
          {admin && <ActionButton label="执行周期考核" path={`/admin/member-cycles/${encode(member)}/evaluate`} onDone={cycle.refresh} />}
          {admin && <ActionButton label="补发当前周期权益" path={`/admin/member-cycle-benefits/${encode(member)}/grant`} onDone={cycle.refresh} />}
          {!admin && <Button href="#benefits">查看我的权益</Button>}
        </Space>
        {admin && <p className="muted">补发受理后请在权益发放页面查看到账状态；重复补发不会增加相同周期礼包。</p>}
      </Card>
    </>}
    {!cycle.data && !cycle.error && <Alert type="info" title={cycle.loading ? "正在读取周期考核" : "选择会员后查看周期与保级情况"} />}
    {admin && <>
      <Card title="周期规则" extra={<CommandModal title="发布周期规则" path="/admin/member-cycles/policies" fields={[
        { name: "version", label: "新策略版本", type: "number", min: 1 }, { name: "effectiveFrom", label: "生效时间与周期锚点", type: "datetime" },
        { name: "periodDays", label: "周期天数", type: "number", min: 1, max: 366 },
        { name: "levels", label: "等级门槛", type: "textarea", help: "每行 等级代码=整数成长门槛，首档为0，最多8档。" },
      ]} build={v => ({ ...v, effectiveFrom: instant(v.effectiveFrom), levels: String(v.levels).split("\n").filter(l => l.trim()).map(line => {
        const [code, raw, ...extra] = line.split("="); const minimumGrowth = Number(raw);
        if (extra.length || !code.trim() || raw === undefined || !Number.isSafeInteger(minimumGrowth)) throw new Error("等级请使用 等级代码=整数门槛 格式");
        return { code: code.trim(), minimumGrowth };
      }) })} onDone={policies.refresh} />}>
        <p className="muted">发布后接管等级考核；成长获取比例仍由成长规则维护。新版本重新锚定周期，历史订单不追溯归入新周期。</p>
        <ErrorNotice error={policies.error} />
        <Table<Policy> rowKey="version" dataSource={policies.data} loading={policies.loading} pagination={false} scroll={{ x: 680 }} columns={[
          { title: "版本", dataIndex: "version" }, { title: "生效时间", dataIndex: "effectiveFrom", render: time }, { title: "周期（天）", dataIndex: "periodDays", align: "right" },
          { title: "等级门槛", render: (_, r) => r.levels.map(l => `${l.code} ≥ ${l.minimumGrowth}`).join(" / ") },
        ]} />
        <Space className="section-actions"><Button disabled={!after} onClick={() => setAfter(0)}>最早周期策略</Button><Button disabled={policies.data?.length !== 50} onClick={() => setAfter(policies.data!.at(-1)!.version)}>下一页周期策略</Button></Space>
      </Card>
      <Card title="等级权益礼包" extra={<CommandModal title="绑定等级礼包" path="/admin/member-cycle-benefits" fields={[
        { name: "bindingId", label: "礼包标识" }, { name: "policyVersion", label: "周期策略版本", type: "number", min: 1 },
        { name: "level", label: "等级代码" }, { name: "storeId", label: "权益所属门店" }, { name: "validUntil", label: "发放截止时间", type: "datetime" },
        { name: "benefits", label: "权益引用", type: "textarea", help: "每行 权益标识=版本号，最多8项；权益定义须覆盖礼包发放窗口。" },
      ]} build={v => ({ ...v, validUntil: instant(v.validUntil), benefits: String(v.benefits).split("\n").filter(l => l.trim()).map(line => {
        const [benefitId, raw, ...extra] = line.split("="); const version = Number(raw);
        if (extra.length || !benefitId.trim() || !Number.isSafeInteger(version) || version <= 0) throw new Error("权益请使用 权益标识=正整数版本 格式");
        return { benefitId: benefitId.trim(), version };
      }) })} onDone={bundles.refresh} />}>
        <Space><span>周期策略版本</span><InputNumber aria-label="礼包周期策略版本" min={1} precision={0} value={policyVersion} onChange={v => v && setPolicyVersion(v)} /><Button onClick={bundles.refresh}>刷新礼包</Button></Space>
        <p className="muted">每策略每等级一个不可变礼包。生效后自动发放，配额不足进入待处理事件；后台可在配额问题解决后重试。</p>
        <ErrorNotice error={bundles.error} />
        <Table<Bundle> rowKey="bindingId" dataSource={bundles.data} loading={bundles.loading} pagination={false} scroll={{ x: 700 }} columns={[
          { title: "礼包", dataIndex: "bindingId" }, { title: "等级", dataIndex: "level" }, { title: "门店", dataIndex: "storeId" }, { title: "发放截止", dataIndex: "validUntil", render: time },
          { title: "权益", render: (_, r) => r.benefits.map(b => `${b.benefitId} / v${b.version}`).join("、") },
        ]} />
      </Card>
    </>}
  </div>;
}
