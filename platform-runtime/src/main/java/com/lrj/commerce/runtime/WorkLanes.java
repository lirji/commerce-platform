package com.lrj.commerce.runtime;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

/**
 * 后台车道登记处：各模块在自己的服务中创建轮转并登记积压查询，平台运维从这里读取全部车道的低基数观测，
 * 不需要跨模块读取业务表；积压查询只在读取健康时执行，结果缓存5秒。
 */
@Component
public class WorkLanes {
    /** 车道积压：到期未处理项数、最老到期项等待秒数、已停止自动处理（隔离或自动核对用尽）的项数；全局聚合不含租户。 */
    public record Backlog(long due,Long oldestDueAgeSeconds,long quarantined) { }
    public record Lane(TenantRotation.Stats rotation,Backlog backlog) { }
    private record Entry(TenantRotation rotation,Supplier<Backlog> backlog) { }
    private final Map<String,Entry> lanes=new ConcurrentSkipListMap<>();
    private volatile Map<String,Backlog> cached=Map.of();private volatile long cachedAt;
    /** 车道名全局唯一，重复登记说明同一工作被两个服务调度，启动即失败。 */
    public TenantRotation rotation(String lane,TenantRotation.Policy policy,Supplier<Backlog> backlog) {
        var rotation=new TenantRotation(lane,policy);
        if(lanes.putIfAbsent(lane,new Entry(rotation,backlog))!=null)throw new IllegalStateException("重复后台车道");
        return rotation;
    }
    /** 积压查询失败时该车道积压为空，不影响其他车道。 */
    public Map<String,Lane> snapshot() {
        if(System.nanoTime()-cachedAt>5_000_000_000L||cached.isEmpty()) {
            var next=new TreeMap<String,Backlog>();
            for(var e:lanes.entrySet()){if(e.getValue().backlog()==null)continue;try{next.put(e.getKey(),e.getValue().backlog().get());}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("lane backlog unavailable lane={} errorType={}",e.getKey(),unavailable.getClass().getSimpleName());}}
            cached=next;cachedAt=System.nanoTime();
        }
        var result=new TreeMap<String,Lane>();
        for(var e:lanes.entrySet())result.put(e.getKey(),new Lane(e.getValue().rotation().stats(),cached.get(e.getKey())));
        return result;
    }
}
