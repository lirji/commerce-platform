-- V11已应用，继续用追加约束保护权益生命周期与余额，不改历史校验和。
ALTER TABLE benefit_grant
 ADD CONSTRAINT ck_grant_spendable_balance CHECK((status='AVAILABLE' AND remaining_units>0) OR (status<>'AVAILABLE' AND remaining_units=0)),
 ADD CONSTRAINT ck_grant_compensation_debt CHECK((status='COMPENSATION_REQUIRED' AND debt_units>0) OR (status<>'COMPENSATION_REQUIRED' AND debt_units=0)),
 ADD CONSTRAINT ck_grant_confirmed_expiry CHECK(status IN ('RESERVED','CANCELLED') OR expires_at IS NOT NULL);
