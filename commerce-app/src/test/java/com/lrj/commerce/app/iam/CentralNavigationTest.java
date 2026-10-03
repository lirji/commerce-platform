package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.NavigationDtos.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无访问能力、错误映射与只读安全链分开验证，不把空导航当旧管理员回退。 */
class CentralNavigationTest {
    private static View view() {
        var c=new AccessContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,1,1,UUID.randomUUID().toString(),"commerce","test","commerce-nav","HUMAN",UUID.randomUUID().toString());
        return new View("1",UUID.randomUUID().toString(),c,1,"a".repeat(64),"b".repeat(64),Instant.now().toString(),"NO_ACCESS",List.of(),List.of());
    }
    @Test void currentCentralContextNeedsExactLocalBindingEvenWithoutAnyCapability() {
        var client=mock(CentralAccessClient.class);var bindings=mock(CentralStoreBindingMapper.class);var v=view();
        when(client.navigation(anyString(),any())).thenReturn(v);
        var service=new CentralNavigationService(client,bindings);var c=v.context();
        assertThrows(AccessDeniedException.class,()->service.current("token",c.tenantId(),1L));
        when(bindings.find(c.tenantId(),c.principalId(),c.membershipId(),1)).thenReturn(new Actor("local-tenant","operator",Actor.Role.OPERATOR));
        assertEquals(v,service.current("token",c.tenantId(),1L));
        when(bindings.find(c.tenantId(),c.principalId(),c.membershipId(),1)).thenReturn(new Actor("local-tenant","admin",Actor.Role.ADMIN));
        assertThrows(AccessDeniedException.class,()->service.current("token",c.tenantId(),1L));
    }
    @Test void upstreamFailureCannotReadLocalActorOrUseOldPermissions() {
        var client=mock(CentralAccessClient.class);var bindings=mock(CentralStoreBindingMapper.class);
        when(client.navigation(anyString(),any())).thenThrow(new CentralAccessException(503));
        assertThrows(CentralAccessException.class,()->new CentralNavigationService(client,bindings).current("token",UUID.randomUUID().toString(),null));
        verifyNoInteractions(bindings);
    }
    @Test void filterOnlyCarriesNavigationAuthorityAndAlwaysClearsContext() throws Exception {
        var service=mock(CentralNavigationService.class);var v=view();when(service.current(anyString(),anyString(),isNull())).thenReturn(v);
        var filter=new CentralNavigationConfiguration.NavigationFilter(service);
        var request=new MockHttpServletRequest("GET","/v1/operations/navigation");request.addHeader("Authorization","Bearer token");request.addHeader("X-Tenant-Id",v.context().tenantId());
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{
            var auth=SecurityContextHolder.getContext().getAuthentication();assertEquals(v,auth.getPrincipal());
            assertEquals(List.of("CENTRAL_NAVIGATION"),auth.getAuthorities().stream().map(Object::toString).toList());
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());assertEquals("no-store",response.getHeader("Cache-Control"));
        var forged=new MockHttpServletRequest("GET","/v1/operations/navigation");forged.addHeader("Authorization","Bearer token");forged.addHeader("X-Tenant-Id",v.context().tenantId());forged.addParameter("principal_id","other");
        var invalid=new MockHttpServletResponse();filter.doFilter(forged,invalid,(req,res)->fail("invalid query reached controller"));assertEquals(400,invalid.getStatus());
        var duplicated=new MockHttpServletRequest("GET","/v1/operations/navigation");duplicated.addHeader("Authorization","Bearer token");duplicated.addHeader("Authorization","Bearer other");duplicated.addHeader("X-Tenant-Id",v.context().tenantId());
        var denied=new MockHttpServletResponse();filter.doFilter(duplicated,denied,(req,res)->fail("duplicate header reached controller"));assertEquals(401,denied.getStatus());
        var write=new MockHttpServletRequest("POST","/v1/operations/navigation");var rejected=new MockHttpServletResponse();filter.doFilter(write,rejected,(req,res)->fail("write reached controller"));assertEquals(403,rejected.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());verify(service,times(1)).current(anyString(),anyString(),isNull());
    }
}
