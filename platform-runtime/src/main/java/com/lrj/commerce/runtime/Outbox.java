package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** 只持久化待投递事实；写入成功不等于消费者或外部渠道完成。 */
@Component
public class Outbox {
    private final EventMapper mapper;
    public Outbox(EventMapper mapper) {this.mapper=mapper;}
    /** 强制加入业务事务，避免数据库已提交而事件丢失。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String tenant,String type,String aggregate,long version,Object payload) {
        mapper.insert(UUID.randomUUID().toString(),tenant,type,aggregate,version,JsonCodec.write(payload));
    }
}
