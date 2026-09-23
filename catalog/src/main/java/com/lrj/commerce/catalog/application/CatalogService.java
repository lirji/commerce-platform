package com.lrj.commerce.catalog.application;
import com.lrj.commerce.catalog.api.CatalogApi;
import com.lrj.commerce.catalog.infrastructure.CatalogMapper;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** 商品发布价格是服务端事实，报价请求不能携带任意价格。 */
@Service
public class CatalogService implements CatalogApi {
    private final java.time.Clock clock;private final CatalogMapper mapper; private final StoreApi stores; private final Commands commands;
    public CatalogService(CatalogMapper mapper,StoreApi stores,Commands commands,java.time.Clock clock) {this.clock=clock;this.mapper=mapper;this.stores=stores;this.commands=commands;}
    /** 总览是租户管理员能力，门店有效性仍由权威服务判断。 */
    public Stats stats(Actor actor,String store){actor.requireAdmin();stores.requireActive(actor,store);return mapper.stats(actor.tenantId(),store);}
    /** 初始发布快照不可变，重复命令返回原创建结果。 */
    public View create(Actor actor,String key,Create input) {
        actor.requireAdmin(); Inputs.require(input!=null,"请求不能为空");
        Identifiers.require(input.skuId()); Inputs.text(input.title(),128); Inputs.text(input.unitPrice(),32);
        Money price;
        try { price=new Money(new BigDecimal(input.unitPrice())); }
        catch(NumberFormatException ex) {throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}
        var normalized=new Create(input.skuId(),input.storeId(),input.title(),price.amount().toPlainString());
        return commands.run(actor,"catalog.create",key,normalized,View.class,()->{
            stores.requireActive(actor,input.storeId()); mapper.insert(actor.tenantId(),normalized);
            mapper.snapshot(actor.tenantId(),input.skuId(),"兼容接口创建商品",actor.actorId());
            return published(actor,input.storeId(),List.of(input.skuId())).getFirst();
        });
    }
    /** 目录是同租户可见数据，冻结店铺不能继续销售。 */
    public List<View> list(Actor actor,String storeId,String after,int limit) {
        stores.requireActive(actor,storeId); Inputs.page(after,limit); var rows=mapper.list(actor.tenantId(),storeId,after,limit);if(rows.isEmpty())return rows;return priced(actor,storeId,rows.stream().map(View::skuId).toList(),clock.instant()).stream().map(p->new View(p.skuId(),p.storeId(),p.title(),p.unitPrice(),p.revision(),"ACTIVE")).toList();
    }
    /** 一次批量查询同时选价，避免逐SKU访问渠道价。 */
    public List<Price> priced(Actor actor,String store,List<String> ids,java.time.Instant now){
        stores.requireActive(actor,store);Inputs.require(ids!=null&&!ids.isEmpty()&&ids.size()<=100,"SKU数量无效");ids.forEach(Identifiers::require);
        var values=mapper.priced(actor.tenantId(),store,ids,actor.channel().name(),now);
        if(values.size()!=new java.util.HashSet<>(ids).size())throw new DomainException(DomainException.Code.NOT_FOUND,"商品不存在或未发布");return values;
    }
    /** 批次有界，缺失与非当前店铺SKU统一拒绝。 */
    public List<View> published(Actor actor,String storeId,List<String> ids) {
        Inputs.require(ids!=null&&!ids.isEmpty()&&ids.size()<=100,"SKU数量无效"); ids.forEach(Identifiers::require);
        var values=mapper.batch(actor.tenantId(),storeId,ids);
        if(values.size()!=new java.util.HashSet<>(ids).size()) throw new DomainException(DomainException.Code.NOT_FOUND,"商品不存在或未发布");
        return values;
    }
}
