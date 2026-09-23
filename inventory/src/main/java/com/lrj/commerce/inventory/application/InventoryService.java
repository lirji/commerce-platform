package com.lrj.commerce.inventory.application;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.inventory.infrastructure.InventoryMapper;
import com.lrj.commerce.catalog.api.CatalogApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 库存没有远程调用，在订单事务内有条件扣减防止超卖。 */
@Service
public class InventoryService implements InventoryApi {
    private final InventoryMapper mapper;private final Commands commands;private final CatalogApi catalog;private final StoreApi stores;
    public InventoryService(InventoryMapper mapper,Commands commands,CatalogApi catalog,StoreApi stores) {this.mapper=mapper;this.commands=commands;this.catalog=catalog;this.stores=stores;}
    /** 收货不是前端直接改库存，使用受审计的增量命令。 */
    public Stock receive(Actor actor,String key,Receipt input) {
        actor.requireAdmin();Inputs.require(input!=null&&input.quantity()>0&&input.quantity()<=1000000,"入库数量无效");
        return commands.run(actor,"inventory.receive",key,input,Stock.class,()->{
            stores.requireActive(actor,input.storeId());catalog.published(actor,input.storeId(),List.of(input.skuId()));
            mapper.receive(actor.tenantId(),input);return mapper.find(actor.tenantId(),input.storeId(),input.skuId());
        });
    }
    /** 仅管理员可看到租户库存总量。 */
    public List<Stock> list(Actor actor,String storeId,String after,int limit) {actor.requireAdmin();Identifiers.require(storeId);Inputs.page(after,limit);return mapper.list(actor.tenantId(),storeId,after,limit);}
    /** 订单用例已按SKU排序；一项不足抛出异常回滚此前所有项。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(Actor actor,String orderId,String storeId,String skuId,int quantity) {
        Identifiers.require(orderId);Identifiers.require(storeId);Identifiers.require(skuId);Inputs.require(quantity>0&&quantity<=10000,"预占数量无效");
        if(mapper.reserve(actor.tenantId(),storeId,skuId,quantity)!=1) throw new DomainException(DomainException.Code.CONFLICT,"可售库存不足");
        mapper.insertHold(actor.tenantId(),orderId,storeId,skuId,quantity);
    }
    /** 已确认保持幂等，已释放则不能确认。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(String tenant,String order) {finish(tenant,order,true);}
    /** 已释放保持幂等，已确认不能通过取消归还。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String tenant,String order) {finish(tenant,order,false);}
    private void finish(String tenant,String order,boolean confirm) {
        for(var hold:mapper.holds(tenant,order)) {
            String target=confirm?"CONFIRMED":"RELEASED";
            if(hold.status().equals(target)) continue;
            if(!hold.status().equals("RESERVED")) throw new DomainException(DomainException.Code.CONFLICT,"库存预占终态冲突");
            int changed=confirm?mapper.confirmStock(tenant,hold):mapper.releaseStock(tenant,hold);
            if(changed!=1||mapper.terminal(tenant,order,hold.skuId(),target)!=1) throw new DomainException(DomainException.Code.CONFLICT,"库存并发状态冲突");
        }
    }
    /** 原预占行锁串行累计退货数量，退货台账和可售回补同事务。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void returnItems(String tenant,String order,String caseId,String sku,int quantity){
        Inputs.require(quantity>0&&quantity<=10000,"退货数量无效");
        var hold=mapper.holds(tenant,order).stream().filter(h->h.skuId().equals(sku)).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"原库存确认不存在"));
        Integer previous=mapper.returned(tenant,caseId,sku);if(previous!=null){if(previous!=quantity)throw new DomainException(DomainException.Code.CONFLICT,"退货幂等数量冲突");return;}
        if(mapper.addReturned(tenant,order,sku,quantity)!=1||mapper.restore(tenant,hold.storeId(),sku,quantity)!=1)throw new DomainException(DomainException.Code.CONFLICT,"累计退货超出已确认数量");
        mapper.recordReturn(tenant,caseId,sku,quantity);
    }
}
