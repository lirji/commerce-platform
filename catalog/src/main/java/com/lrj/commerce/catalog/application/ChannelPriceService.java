package com.lrj.commerce.catalog.application;
import com.lrj.commerce.catalog.api.ChannelPriceApi;
import com.lrj.commerce.catalog.infrastructure.*;
import com.lrj.commerce.store.api.StoreAccessApi;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
/** 修改渠道价不改基础价或旧报价；失效后销售自动回退基础价。 */
@Service
public class ChannelPriceService implements ChannelPriceApi {
 private final ChannelPriceMapper mapper;private final ProductMapper products;private final StoreAccessApi access;private final Commands commands;private final Clock clock;
 public ChannelPriceService(ChannelPriceMapper mapper,ProductMapper products,StoreAccessApi access,Commands commands,Clock clock){this.mapper=mapper;this.products=products;this.access=access;this.commands=commands;this.clock=clock;}
 /** 锁SKU串行化首次创建与后续CAS，避免缺行时锁不到价格。 */
 public Price change(Actor actor,String key,String sku,Change input){Identifiers.require(sku);Inputs.require(input!=null&&input.channel()!=null&&input.expectedVersion()>=0&&input.validFrom()!=null&&input.validTo()!=null,"渠道价参数无效");Inputs.text(input.reason(),256);access.requireCatalog(actor,input.storeId());String price;try{price=new Money(new BigDecimal(Inputs.text(input.unitPrice(),32))).amount().toPlainString();}catch(NumberFormatException e){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额无效");}
  var normalized=new Change(input.storeId(),input.channel(),input.expectedVersion(),price,input.validFrom(),input.validTo(),input.active(),input.reason());
  return commands.run(actor,"catalog.channel-price",key,new Object[]{sku,normalized},Price.class,()->{access.requireCatalog(actor,input.storeId());var now=clock.instant();Inputs.require(input.validTo().isAfter(now)&&input.validTo().isAfter(input.validFrom())&&!input.validFrom().isAfter(now.plus(Duration.ofDays(366)))&&Duration.between(input.validFrom(),input.validTo()).compareTo(Duration.ofDays(366))<=0,"渠道价有效期无效");Inputs.found(products.skuLock(actor.tenantId(),input.storeId(),sku));var old=mapper.current(actor.tenantId(),input.storeId(),sku,input.channel().name());long version=old==null?0:old.version();if(version!=input.expectedVersion())throw new DomainException(DomainException.Code.CONFLICT,"渠道价版本已变化");var result=new Price(sku,input.storeId(),input.channel(),version+1,normalized.unitPrice(),input.validFrom(),input.validTo(),input.active(),input.reason(),actor.actorId(),now);mapper.save(actor.tenantId(),result);mapper.snapshot(actor.tenantId(),result);return result;});
 }
 /** 经营读取包含禁用和未开始价格，以便审查投放窗口。 */
 public List<Price> list(Actor actor,String store,String sku){access.requireCatalog(actor,store);Identifiers.require(sku);Inputs.found(products.sku(actor.tenantId(),store,sku));return mapper.list(actor.tenantId(),store,sku);}
 /** 历史按不可变版本有界读取。 */
 public List<Price> history(Actor actor,String store,String sku,Actor.Channel channel,long after,int limit){access.requireCatalog(actor,store);Identifiers.require(sku);Inputs.require(channel!=null&&after>=0,"渠道或版本无效");Inputs.page("",limit);Inputs.found(products.sku(actor.tenantId(),store,sku));return mapper.history(actor.tenantId(),store,sku,channel.name(),after,limit);}
}
