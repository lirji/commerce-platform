package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.persistence.EventMapper;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
/** 数据库领取、Inbox、副作用与投递标记同事务，崩溃时由数据库释放锁恢复。 */
@Component
public class EventDispatcher {
    private final EventMapper mapper;private final List<EventHandler> handlers;private final Set<String> types;
    private final TransactionTemplate tx;private final Commands commands;private String cursor="";
    public EventDispatcher(EventMapper mapper,List<EventHandler> handlers,PlatformTransactionManager manager,Commands commands) {
        this.mapper=mapper;this.handlers=List.copyOf(handlers);this.commands=commands;this.tx=new TransactionTemplate(manager);tx.setTimeout(10);
        var names=new HashSet<String>();var all=new HashSet<String>();
        for(var h:handlers){if(!names.add(h.consumer()))throw new IllegalArgumentException("重复事件消费者");all.addAll(h.types());}types=Set.copyOf(all);
    }
    /** 每个租户一轮最多5条，轮转游标防止低字典序租户饿死其他租户。 */
    public synchronized int tick() {
        if(types.isEmpty())return 0;var tenants=mapper.tenants(types,cursor);
        if(tenants.isEmpty()){cursor="";return 0;}
        int count=0;for(var tenant:tenants)count+=pumpTenant(tenant);cursor=tenants.getLast();return count;
    }
    /** 管理员只可触发自己租户的持久事件。 */
    public int pump(Actor actor) {actor.requireAdmin();return pumpTenant(actor.tenantId());}
    private int pumpTenant(String tenant) {
        if(types.isEmpty())return 0;int count=0;
        for(var candidate:mapper.pending(tenant,types,5)) {
            try {
                Boolean processed=tx.execute(status->{
                    var event=mapper.lock(tenant,candidate.eventId());if(event==null)return false;
                    for(var h:handlers)if(h.types().contains(event.eventType())&&mapper.inbox(h.consumer(),event)==1)h.handle(event);
                    if(mapper.delivered(event.eventId())!=1)throw new IllegalStateException("事件投递版本冲突");return true;
                });
                if(Boolean.TRUE.equals(processed))count++;
            } catch(RuntimeException failure) {
                // 先回滚消费事务，另事务记录失败；不保存异常文本避免载荷泄漏。
                tx.executeWithoutResult(s->mapper.failed(candidate.eventId(),(1<<Math.min(candidate.attempts()+1,5))+java.util.concurrent.ThreadLocalRandom.current().nextInt(2)));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("event retry id={} errorType={}",candidate.eventId(),failure.getClass().getSimpleName());
            }
        }return count;
    }
    /** 运维查询只返回元信息，不返回业务载荷或异常内容。 */
    public List<EventMapper.EventView> list(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
    /** 隔离重放需要显式命令和审计，不无限自动重试。 */
    public int retry(Actor actor,String key,String id){actor.requireAdmin();Identifiers.require(id);return commands.run(actor,"event.retry",key,id,Integer.class,()->{if(mapper.retry(actor.tenantId(),id)!=1)throw new DomainException(DomainException.Code.CONFLICT,"事件不在可重放状态");return 1;});}
}
