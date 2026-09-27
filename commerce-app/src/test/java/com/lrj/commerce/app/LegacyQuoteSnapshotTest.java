package com.lrj.commerce.app;

import com.lrj.commerce.trade.application.QuoteService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 8c20699时期写入的券报价快照缺少platformFundingBps，营销效果消费者读取时曾以MismatchedInputException失败。 */
class LegacyQuoteSnapshotTest {

	/** 取自测试库真实历史行，仅替换商品标题。 */
	static final String LEGACY = "{\"quoteId\":\"11fb0c5d-5282-47ff-91e7-635d342d3048\",\"memberId\":\"m1\",\"merchantId\":\"merchant1\",\"storeId\":\"store1\",\"currency\":\"CNY\",\"gross\":\"25.00\",\"discount\":\"8.00\",\"payable\":\"17.00\",\"createdAt\":\"2026-09-23T10:24:51.885Z\",\"expiresAt\":\"2026-09-23T10:29:51.885Z\",\"items\":[{\"skuId\":\"sku1\",\"revision\":1,\"title\":\"商品\",\"quantity\":1,\"unitPrice\":\"25.00\",\"gross\":\"25.00\",\"discount\":\"8.00\",\"payable\":\"17.00\"}],\"campaign\":{\"campaignId\":\"stack\",\"version\":1},\"trace\":[{\"campaignId\":\"stack\",\"version\":1,\"reason\":\"ELIGIBLE\"}],\"sources\":[],\"coupon\":{\"couponId\":\"3b33d653-400a-4dce-9107-e4008538c374\",\"definitionId\":\"coupon-def\",\"version\":1,\"discount\":\"5.00\"},\"campaignDiscount\":\"3.00\",\"couponStatus\":\"APPLIED\"}";

	@Test
	void legacyCouponSnapshotDefaultsToMerchantFunded() {
		var view = QuoteService.snapshot(LEGACY);
		// 与V10迁移对既有券定义的默认值一致：平台承担0，券优惠全部由商家承担。
		assertEquals(0, view.coupon().platformFundingBps());
		assertEquals("5.00", view.coupon().discount());
		assertEquals("17.00", view.payable());
	}

	@Test
	void currentSnapshotsKeepTheirFundingAndOtherGapsStillFail() {
		assertEquals(2500, QuoteService
			.snapshot(LEGACY.replace("\"discount\":\"5.00\"}", "\"discount\":\"5.00\",\"platformFundingBps\":2500}"))
			.coupon()
			.platformFundingBps());
		assertNull(QuoteService.snapshot(LEGACY.replaceAll("\"coupon\":\\{[^}]*}", "\"coupon\":null")).coupon());
		// 只补迁移已定义默认值的字段，其他缺失的必填基本类型仍然失败，不静默猜测。
		assertThrows(RuntimeException.class, () -> QuoteService
			.snapshot(LEGACY.replace("\"version\":1,\"discount\":\"5.00\"", "\"discount\":\"5.00\"")));
	}

}
