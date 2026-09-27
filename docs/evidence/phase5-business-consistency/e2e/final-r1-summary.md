# Phase 5 browser run `final-r1`

Command: `bash docs/evidence/phase5-business-consistency/scripts/browser-e2e.sh final-r1`. The app was the clean packaged jar with background workers enabled, connected to the isolated `commerce_test_20260923` MySQL schema. Fixture scripts created fresh UUID tenants. Playwright used one worker and no retries. Result: **20 passed, 4 failed, 24 total** in approximately 1.8 minutes.

| Spec | Result | Observed assertion | Classification |
|---|---|---|---|
| `catalog-merchandising.spec.ts` | failed | 12-second timeout waiting for `筛选商品` button at line 124 | `KNOWN_EXISTING`, same Phase 4 failure |
| `coupon-deliveries.spec.ts` | failed | expected dialog text `撤销 2`, actual receipt summary `撤销 / 保留 2 / 0` at line 74 | `KNOWN_EXISTING`, same Phase 4 failure |
| `member-behavior.spec.ts` | failed | 12-second timeout waiting for `查看商品` button on `积分精品咖啡` at line 41 | `KNOWN_EXISTING`, same Phase 4 failure |
| `member-cycles.spec.ts` | failed | expected body `color-scheme: dark`, actual `light` at line 49 | `KNOWN_EXISTING`, same Phase 4 failure |
| `operations.spec.ts` journey and after-sales | passed | payment → fulfillment readiness → after-sales completed without the intermittent 409 | `RESOLVED` for the test readiness race |

The four failure titles and assertion sites match Phase 4 `final-r3`; generated IDs, timestamps and DOM identifiers naturally vary. There was no new deterministic browser failure. The browser JSON, full logs and PNG screenshots remain in this local evidence directory but are ignored by the repository's existing `docs/**/*.json`, `docs/**/*.png` and `*.log` rules. This text summary is the reviewable version-controlled evidence. The app log's generated development password was redacted before storage.
