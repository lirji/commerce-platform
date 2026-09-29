package com.lrj.commerce.store.access.api;

/** 认证适配器只读取迁移单元选择，不访问store持久化实现。 */
public interface CatalogAuthorityRoutes {
    /** 持久状态使用显式稳定code，CENTRAL之后只能停止或恢复中央。 */
    enum State {
        LEGACY("LEGACY"), SHADOW("SHADOW"), CENTRAL("CENTRAL"), STOPPED("STOPPED");
        private final String code;
        State(String code){this.code=code;}
        public String code(){return code;}
        /** 未知数据库状态不能按旧权威放行。 */
        public static State fromCode(String code){for(var state:values())if(state.code.equals(code))return state;throw new IllegalStateException("未知经营权威状态");}
        public boolean legacyAuthority(){return this==LEGACY||this==SHADOW;}
    }
    record Route(String tenantId,String authTenantId,String state,boolean frozen,boolean everCentral,long version) {}
    /** 中央模式的单元；null表示不可使用中央经营入口。 */
    Route central(String authTenant);
}
