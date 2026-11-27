package com.lrj.commerce.store.management.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import java.time.Instant;
import java.util.List;

/** 开店资料仅开放给负责开店的运营身份，买家目录继续读店铺公开投影。 */
public interface StoreOpeningApi {

	/** 申请时声明的主体类型，枚举名即版本化接口值。 */
	enum SubjectType {
		PERSONAL, INDIVIDUAL, ENTERPRISE;

		/** 只接受稳定字符串代码，避免整数按枚举顺序绑定到经营主体。 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static SubjectType fromCode(String code) {
			return valueOf(code);
		}
	}

	record Draft(String storeId, String merchantId, String name, SubjectType subjectType, String category,
			@JsonInclude(JsonInclude.Include.NON_NULL) String categoryCode) {
		public Draft(String storeId, String merchantId, String name, SubjectType subjectType, String category) {
			this(storeId, merchantId, name, subjectType, category, null);
		}
	}

	record Revision(Long expectedVersion, String name, SubjectType subjectType, String category,
			@JsonInclude(JsonInclude.Include.NON_NULL) String categoryCode) {
		public Revision(Long expectedVersion, String name, SubjectType subjectType, String category) {
			this(expectedVersion, name, subjectType, category, null);
		}
	}

	record Activation(Long expectedVersion, String reviewReference) {
	}

	record Opening(String storeId, String merchantId, String name, String status, long version,
			SubjectType subjectType, String category, String reviewReference, String openedBy, Instant openedAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) String categoryCode) {
	}

	record Category(String code, String label) {
	}

	/** 开店选择使用平台字典，仅列出启用的二级类目。 */
	List<Category> categories(Actor actor);

	/** 保存待开店记录并预留店铺编号。 */
	Opening draft(Actor actor, String key, Draft input);

	/** 按可信请求标识记录草稿。 */
	Opening draft(Actor actor, String key, Draft input, String traceId);

	/** 查询本租户开店资料，允许刷新后从草稿继续。 */
	Opening detail(Actor actor, String storeId);

	/** 只允许修改待开店资料，确认开店必须针对更新后的版本。 */
	Opening revise(Actor actor, String key, String storeId, Revision input);

	/** 按可信请求标识记录资料变更。 */
	Opening revise(Actor actor, String key, String storeId, Revision input, String traceId);

	/** 记录实际审核依据后启用此店，启用与审计同事务。 */
	Opening open(Actor actor, String key, String storeId, Activation input);

	/** 按可信请求标识记录开通。 */
	Opening open(Actor actor, String key, String storeId, Activation input, String traceId);

	/** 审计记录沿用当前开店资料授权，只查询本租户的指定店铺。 */
	OperationAudit.Page history(Actor actor, String storeId, String traceId, String cursor);
}
