ALTER TABLE aftersales_line
 ADD COLUMN points BIGINT NOT NULL DEFAULT 0 COMMENT '按原订单SKU数量累计分配的名义返还积分',
 ADD CONSTRAINT ck_aftersale_points CHECK(points>=0);
