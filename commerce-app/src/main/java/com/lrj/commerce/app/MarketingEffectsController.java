package com.lrj.commerce.app;
import com.lrj.commerce.insight.api.MarketingEffectsApi;
import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/** 经营效果只开放管理读取及有界重建；不提供任意SQL或跨租户报表。 */
@RestController @RequestMapping("/v1/admin")
public class MarketingEffectsController {
 private final MarketingEffectsApi effects;private final JourneyApi journeys;
 public MarketingEffectsController(MarketingEffectsApi effects,JourneyApi journeys){this.effects=effects;this.journeys=journeys;}
 /** 活动版本的成交退款与优惠承担。 */
 @GetMapping("/marketing-effects") public Object report(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return effects.report(actor,storeId,from,to,after,limit);}
 /** 对历史订单补建投影，每次最多100单。 */
 @PostMapping("/marketing-effects/rebuild") public Object rebuild(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MarketingEffectsApi.Rebuild input){return effects.rebuild(actor,key,input);}
 /** 旅程执行指标不混入成交归因。 */
 @GetMapping("/journey-effects") public Object journeys(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return journeys.effects(actor,storeId,from,to,after,limit);}
}
