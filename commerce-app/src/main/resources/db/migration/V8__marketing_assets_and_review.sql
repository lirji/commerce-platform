CREATE TABLE marketing_audience_snapshot (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 audience_id VARCHAR(64) NOT NULL COMMENT '人群定义标识',
 version BIGINT NOT NULL COMMENT '不可变快照版本',
 name VARCHAR(128) NOT NULL COMMENT '人群名称',
 source VARCHAR(128) NOT NULL COMMENT '可信导入来源标识',
 watermark TIMESTAMP(3) NOT NULL COMMENT '数据水位UTC',
 valid_until TIMESTAMP(3) NOT NULL COMMENT '新鲜度截止时间不含端点',
 member_count INT NOT NULL COMMENT '本快照成员数',
 PRIMARY KEY(tenant_id,audience_id,version),
 CONSTRAINT ck_audience_window CHECK(version>0 AND valid_until>watermark AND member_count>=0 AND member_count<=500)
) COMMENT='营销可信人群不可变快照';
CREATE TABLE marketing_audience_member (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 audience_id VARCHAR(64) NOT NULL COMMENT '所属人群',
 version BIGINT NOT NULL COMMENT '固定快照版本',
 member_id VARCHAR(64) NOT NULL COMMENT '平台会员标识',
 PRIMARY KEY(tenant_id,audience_id,version,member_id)
) COMMENT='营销快照内的成员命中投影';
CREATE TABLE marketing_rule_asset (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 rule_id VARCHAR(64) NOT NULL COMMENT '规则稳定标识',
 version BIGINT NOT NULL COMMENT '不可变规则版本',
 name VARCHAR(128) NOT NULL COMMENT '运营规则名称',
 rule_json TEXT NOT NULL COMMENT '允许字段及有界AST',
 status VARCHAR(16) NOT NULL COMMENT 'DRAFT或PUBLISHED',
 PRIMARY KEY(tenant_id,rule_id,version),
 CONSTRAINT ck_rule_asset CHECK(version>0 AND status IN ('DRAFT','PUBLISHED') AND JSON_VALID(rule_json))
) COMMENT='可复用营销规则版本资产';
ALTER TABLE marketing_campaign
 ADD COLUMN policy_json TEXT NULL COMMENT '受治理规则与人群版本引用空值兼容旧直接发布',
 DROP CHECK marketing_campaign_chk_1,
 ADD CONSTRAINT ck_campaign_governed_status CHECK(status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','PAUSED')),
 ADD CONSTRAINT ck_campaign_policy CHECK(policy_json IS NULL OR JSON_VALID(policy_json));
