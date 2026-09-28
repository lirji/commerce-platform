package com.lrj.commerce.app;

import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.store.management.api.StoreApi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直接new用例及非HTTP调用也不能借伪造本地Actor跳过中央检查。 */
class CentralStoreReadTest {
    @Test void directUnproxiedInvocationRechecksAndNeverReadsWhenDenied() {
        var central = mock(CentralAccessClient.class);
        var binding = mock(CentralStoreBindingMapper.class);
        var stores = mock(StoreApi.class);
        var service = new CentralStoreReadService(central, binding, stores);
        when(central.requireAllowed(anyString(), any())).thenThrow(new AccessDeniedException("denied"));
        var forged = new CentralStoreIdentity("untrusted", "tenant", 1, new Actor("tenant", "admin", Actor.Role.ADMIN));
        assertThrows(AccessDeniedException.class, () -> service.read(forged, "", 20));
        verify(central).requireAllowed(eq("untrusted"), any());
        verifyNoInteractions(binding, stores);
    }
    @Test void missingIdentityCannotReachTheBusinessPort() {
        var central = mock(CentralAccessClient.class);
        var binding = mock(CentralStoreBindingMapper.class);
        var stores = mock(StoreApi.class);
        assertThrows(AccessDeniedException.class, () -> new CentralStoreReadService(central, binding, stores).read(null, "", 20));
        verifyNoInteractions(central, binding, stores);
    }
}
