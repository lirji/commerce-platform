package com.lrj.commerce.ops.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.campaign.api.CampaignApi;
import com.lrj.commerce.benefit.api.CouponApi;
import com.lrj.commerce.journey.api.JourneyApi;
import java.util.List;
/** 页面DSL只引用受控业务能力，不能提供任意执行代码。 */
public interface OpsPageApi {
    enum Source { CAMPAIGNS, JOURNEYS, BUDGETS, COUPONS, ENTITLEMENTS }
    enum ActionKind { CREATE_CAMPAIGN, CREATE_COUPON, ENROLL_JOURNEY }
    record Section(String id,String title,Source source) { }
    record Action(String id,String label,ActionKind kind) { }
    record Definition(String pageId,long version,String title,String storeId,List<Section> sections,List<Action> actions) { }
    record View(Definition content,String status,long lockVersion) { }
    record SectionData(String id,List<?> rows) { }
    record Render(View page,List<SectionData> data,boolean preview,boolean bounded) { }
    record ActionInput(CampaignApi.Draft campaign,CouponApi.Definition coupon,JourneyApi.Start enrollment) { }
    record ActionResult(ActionKind kind,String resourceId,String status) { }
    View create(Actor actor,String key,Definition input);
    List<View> list(Actor actor,String after,int limit);
    List<View> versions(Actor actor,String id);
    View change(Actor actor,String key,String id,long version,long expected,String action);
    Render preview(Actor actor,Definition input);
    Render render(Actor actor,String id);
    ActionResult execute(Actor actor,String key,String id,long version,String action,ActionInput input);
}
