# Final binary quote compatibility

PASS: NEW multi-candidate quote with extended trace OFF -> OLD read 200 -> OLD order 200 -> cancel 200; winner/payable unchanged.

Before the gate fix, OLD reading a NEW quote containing OUTRANKED_BEST_OF returned HTTP 500.
Final jar defaults extended trace OFF; actual API-created two-campaign fixture round-trip above passes.
