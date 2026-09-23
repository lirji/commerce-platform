package com.lrj.commerce.campaign.application;
import com.lrj.commerce.campaign.api.*;
import com.lrj.commerce.campaign.infrastructure.*;
import com.lrj.commerce.marketing.api.DecisionModels.Selection;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.util.List;
/** 营销预算与订单事务相连，确认和取消只允许从预占终态前迁移。 */
@Service
public class CampaignFundingService implements CampaignFundingApi {
    private final BudgetMapper mapper;private final CampaignMapper campaigns;
    public CampaignFundingService(BudgetMapper mapper,CampaignMapper campaigns){this.mapper=mapper;this.campaigns=campaigns;}
    /** 资方比例来自不可变版本，浏览器不能自行指定出资金额。 */
    public Commitment commitment(Actor actor,Selection selected,String discount){if(selected==null)return null;var row=Inputs.found(campaigns.find(actor.tenantId(),selected.campaignId(),selected.version()));var terms=terms(row);long cents=new Money(new BigDecimal(discount)).minorUnits();long platform=BigInteger.valueOf(cents).multiply(BigInteger.valueOf(terms.platformFundingBps())).divide(BigInteger.valueOf(10000)).longValueExact();return new Commitment(selected.campaignId(),selected.version(),discount,Money.minor(platform).amount().toPlainString(),Money.minor(cents-platform).amount().toPlainString(),terms.grant());}
    /** 金额/店铺二次核对且数据库限制总额度，任何失败由下单事务回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(Actor actor,String order,String store,Commitment input){if(input==null)return;var row=Inputs.found(campaigns.find(actor.tenantId(),input.campaignId(),input.version()));if(!row.storeId().equals(store)||!commitment(actor,new Selection(input.campaignId(),input.version()),input.discount()).equals(input)||new BigDecimal(input.discount()).signum()<=0||new BigDecimal(input.discount()).compareTo(new BigDecimal(row.discountAmount()))>0)throw conflict();if(mapper.reserve(actor.tenantId(),input)!=1)throw conflict();mapper.hold(actor.tenantId(),order,input);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(String tenant,String order){finish(tenant,order,true);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String tenant,String order){finish(tenant,order,false);}
    /** 财务列表包括旧版本预算，使用稳定预算ID分页。 */
    public List<Budget> budgets(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
    private void finish(String tenant,String order,boolean confirmed){var hold=mapper.lock(tenant,order);if(hold==null)return;String next=confirmed?"SPENT":"RELEASED";if(hold.status().equals(next))return;if(!hold.status().equals("HELD")||mapper.finishBudget(tenant,hold,confirmed)!=1||mapper.finishHold(tenant,order,next)!=1)throw conflict();}
    private CampaignApi.Terms terms(CampaignMapper.Row row){var policy=row.policyJson()==null?null:JsonCodec.read(row.policyJson(),CampaignApi.Policy.class);return policy==null||policy.terms()==null?new CampaignApi.Terms(0,0,null,null):policy.terms();}
    private DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"活动预算不足或资方快照冲突");}
}
