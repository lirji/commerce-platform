package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.api.UnconsumedEventType;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** 只持久化待投递事实；写入成功不等于消费者或外部渠道完成。 */
@Component
public class Outbox {
    private final EventMapper mapper;private final Map<String,String> skipped=new HashMap<>();private final LongAdder skips=new LongAdder();
    /** 发布方声明无消费者的类型写入即为SKIPPED，行仍保留供审计与重放；同一类型声明两次视为配置错误。 */
    public Outbox(EventMapper mapper,List<UnconsumedEventType> unconsumed) {
        this.mapper=mapper;
        for(var u:unconsumed)if(skipped.put(u.type(),u.reason().name())!=null)throw new IllegalStateException("重复声明无消费者事件："+u.type());
    }
    /** 强制加入业务事务，避免数据库已提交而事件丢失。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String tenant,String type,String aggregate,long version,Object payload) {
        String reason=skipped.get(type);
        mapper.insert(UUID.randomUUID().toString(),tenant,type,aggregate,version,JsonCodec.write(payload),reason);
        if(reason!=null)skips.increment();
    }
    /** 本进程写入即跳过的事件数，低基数计数器。 */
    public long skipped(){return skips.sum();}
}
