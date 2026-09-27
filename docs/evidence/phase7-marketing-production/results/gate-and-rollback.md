# Gate and rollback probe

Final NEW jar with coupon-enabled=false rejects coupon campaign creation: HTTP 409 CONFLICT.
OLD df955f1+V43 quote of coupon campaign: HTTP 200, campaign selected, promotion.coupon absent.
Final NEW+V43 same buyer/campaign quote: HTTP 200, campaign selected, promotion.coupon present.
OLD configuration listing removes terms.coupon. Mixed rollout must keep coupon producers disabled.
