import { RecordFields, RecordHero, money } from "../shared/ui";
import { RuleSummary } from "../shared/marketing";
import type { Campaign, Governed, Rule } from "../shared/contracts";

type RuleAsset = { ruleId: string; version: number; name: string; rule: Rule };

export function MarketingDetails({
  record,
}: {
  record: Governed<Campaign | RuleAsset>;
}) {
  const c = record.content;
  const campaign = "campaignId" in c ? c : undefined;
  return (
    <>
      <RecordHero
        eyebrow={campaign ? "营销活动 · 版本配置" : "动态规则 · 版本配置"}
        title={c.name}
        id={`${campaign ? campaign.campaignId : (c as RuleAsset).ruleId} · v${c.version}`}
        status={record.status}
        metrics={
          campaign
            ? [
                { label: "消费门槛", value: money(campaign.minimumSpend) },
                {
                  label: "优惠金额 / 上限",
                  value: money(campaign.discountAmount),
                },
              ]
            : undefined
        }
      />
      {campaign && (
        <section className="detail-section">
          <h3 className="detail-section-title">活动范围与有效期</h3>
          <RecordFields
            value={{
              storeId: campaign.storeId,
              validFrom: campaign.validFrom,
              validTo: campaign.validTo,
            }}
          />
        </section>
      )}
      <section className="detail-section">
        <h3 className="detail-section-title">会员资格条件</h3>
        <RuleSummary rule={c.rule} />
      </section>
      {campaign?.policy && (
        <section className="detail-section">
          <h3 className="detail-section-title">关联资产与优惠配置</h3>
          {campaign.policy.audience && (
            <RecordFields
              value={{
                audienceId: campaign.policy.audience.id,
                version: campaign.policy.audience.version,
              }}
            />
          )}
          {campaign.policy.rule && (
            <RecordFields
              value={{
                ruleId: campaign.policy.rule.id,
                version: campaign.policy.rule.version,
              }}
            />
          )}
          {campaign.policy.terms && (
            <RecordFields value={campaign.policy.terms} />
          )}
        </section>
      )}
      <section className="detail-section">
        <h3 className="detail-section-title">版本信息</h3>
        <RecordFields
          value={{ version: c.version, lockVersion: record.lockVersion }}
        />
      </section>
      <details className="record-raw">
        <summary>完整原始配置</summary>
        <pre className="json-detail">{JSON.stringify(record, null, 2)}</pre>
      </details>
    </>
  );
}
