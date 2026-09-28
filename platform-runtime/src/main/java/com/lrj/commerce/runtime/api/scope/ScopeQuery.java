package com.lrj.commerce.runtime.api.scope;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;

/** 资源Owner查询端口；调用方必须先验证中央范围，SQL列绑定始终留在拥有表的模块。 */
public interface ScopeQuery {
    /** 类型由适配器固定，不接受任意表名或列名。 */
    String resourceType();
    /** 每条完整授权路径内部AND，不同路径OR；空集合表示拒绝。 */
    record Filter(List<Path> paths) {
        public Filter { paths=List.copyOf(paths);if(paths.size()>100)throw new IllegalArgumentException("范围路径过多"); }
    }
    /** tenantAll只限当前可信租户；门店和资源集合分别匹配固定字段。 */
    record Path(boolean tenantAll,List<String> stores,List<String> resources) {
        public Path {
            stores=List.copyOf(stores);resources=List.copyOf(resources);
            if(stores.size()>100||resources.size()>100||(tenantAll?(!stores.isEmpty()||!resources.isEmpty()):(stores.isEmpty()&&resources.isEmpty())))throw new IllegalArgumentException("范围路径无效");
            for(String id:stores)if(!id.matches("[A-Za-z0-9_:/.-]{1,100}"))throw new IllegalArgumentException("范围标识无效");
            for(String id:resources)if(!id.matches("[A-Za-z0-9_:/.-]{1,100}"))throw new IllegalArgumentException("范围标识无效");
        }
    }
    /** 仅取当前试点展示字段及资源版本，不包含内部机密配置。 */
    record Row(String resourceId,String storeId,String title,long resourceVersion,String merchantId,String status,String category,String brand) {}
    /** 总行数和归属门店数都使用同一授权谓词。 */
    record Stats(long total,long stores) {}
    /** 范围和检索在LIMIT之前执行。 */
    List<Row> page(Actor actor,Filter scope,String search,String after,int limit);
    /** 与分页相同的过滤条件，不泄露未授权总数。 */
    Stats stats(Actor actor,Filter scope,String search);
    /** 只供资源Owner构建可信事实，返回前由中央用例完成授权。 */
    Row fact(Actor actor,String id);
    /** 下载批次验证当前资源事实，最多50行，避免逐行查询。 */
    List<Row> current(Actor actor,Filter scope,List<String> ids);
    /** 复用统一有界输入，所有适配器都拒绝未知本地角色。 */
    static void validate(Actor actor,Filter filter,String search,String after,int limit){
        if(actor==null||actor.role()!=Actor.Role.OPERATOR)throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN,"需要中央运营身份");
        if(filter==null||search==null||search.length()>100||after==null||after.length()>100||limit<1||limit>101)throw new IllegalArgumentException("范围查询参数无效");
    }
}
