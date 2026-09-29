package com.lrj.commerce.store.access.api;

import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.store.management.api.StoreApi;

/** 中央适配端口只校验已选定的本地资源，基础业务不依赖HTTP SDK。 */
public interface CentralCatalogCheck {
    /** 中央模式必须实时检查，缺少引用或依赖失败都停止当前用例。 */
    void require(Actor actor, StoreApi.View store, String authTenant);
}
