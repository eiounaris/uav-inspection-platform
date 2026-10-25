package com.uav.inspection.device;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceAuthenticationFilterTest {
    private static final String ID="20000000-0000-4000-8000-000000000001";
    private static final String RAW="a".repeat(64);
    private static final CurrentDevice DEVICE=new CurrentDevice(ID,"00000000-0000-4000-8000-000000000002","UAV-DEMO-001","b".repeat(64));
    private final DeviceAuthenticationService service=mock(DeviceAuthenticationService.class);
    private final DeviceAuthenticationFilter filter=new DeviceAuthenticationFilter(service,JsonMapper.builder().build());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void validDeviceGetsAnIsolatedPrincipalAndNoTokenCredentialsThenContextIsCleared() throws Exception {
        MockHttpServletRequest request=request("/api/v1/device/reports"); headers(request);
        when(service.authenticate(ID,RAW)).thenReturn(DEVICE);
        AtomicBoolean reached=new AtomicBoolean();
        filter.doFilter(request,new MockHttpServletResponse(),(incoming,response)->{
            reached.set(true);
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            assertSame(DEVICE,authentication.getPrincipal()); assertNull(authentication.getCredentials());
            assertEquals(List.of("ROLE_DEVICE"),authentication.getAuthorities().stream().map(Object::toString).toList());
        });
        assertTrue(reached.get()); assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void aUserJwtOrExistingUserPrincipalCannotReplaceDeviceCredentials() throws Exception {
        MockHttpServletRequest request=request("/api/v1/device/reports"); request.addHeader("Authorization","Bearer user-token");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user",null,List.of()));
        MockHttpServletResponse response=new MockHttpServletResponse(); FilterChain chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);
        assertEquals(401,response.getStatus()); assertEquals("Device",response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
        assertFalse(response.getContentAsString().contains("user-token"));
        assertNull(SecurityContextHolder.getContext().getAuthentication()); verifyNoInteractions(service,chain);
    }

    @Test void duplicateHeadersAreRejectedBeforeAuthentication() throws Exception {
        for(String repeated:List.of("X-Device-Id","X-Device-Token")) {
            MockHttpServletRequest request=request("/api/v1/device/reports"); headers(request);
            request.addHeader(repeated,repeated.equals("X-Device-Id")?ID:RAW);
            MockHttpServletResponse response=new MockHttpServletResponse(); FilterChain chain=mock(FilterChain.class);
            filter.doFilter(request,response,chain); assertEquals(401,response.getStatus()); verifyNoInteractions(chain);
        }
        verifyNoInteractions(service);
    }

    @Test void databaseErrorsReturnSafeUnavailableInsteadOfInvalidDeviceOrRawDiagnostics() throws Exception {
        MockHttpServletRequest request=request("/api/v1/device/reports"); headers(request);
        when(service.authenticate(ID,RAW)).thenThrow(new DataAccessResourceFailureException("provider "+RAW));
        MockHttpServletResponse response=new MockHttpServletResponse(); FilterChain chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus()); assertTrue(response.getContentAsString().contains("DEPENDENCY_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains(RAW)); assertFalse(response.getContentAsString().contains("provider"));
        verifyNoInteractions(chain);
    }

    @Test void databaseTransactionAcquisitionFailuresAlsoReturnUnavailable() throws Exception {
        MockHttpServletRequest request=request("/api/v1/device/reports"); headers(request);
        when(service.authenticate(ID,RAW)).thenThrow(new CannotCreateTransactionException("private connection diagnostic "+RAW));
        MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(request,response,(incoming,outgoing)->fail("Unavailable database must not admit the request"));
        assertEquals(503,response.getStatus()); assertFalse(response.getContentAsString().contains(RAW));
    }

    @Test void deviceHeadersAreIgnoredOutsideTheExactDeviceNamespace() throws Exception {
        for(String path:List.of("/api/v1/drones","/api/v1/device-admin","/api/v1/auth/login")) {
            MockHttpServletRequest request=request(path); headers(request);
            AtomicBoolean reached=new AtomicBoolean();
            filter.doFilter(request,new MockHttpServletResponse(),(incoming,response)->reached.set(true));
            assertTrue(reached.get());
        }
        verifyNoInteractions(service);
    }

    @Test void downstreamPermissionErrorsKeepTheirOwnMeaning() throws Exception {
        MockHttpServletRequest request=request("/api/v1/device/reports"); headers(request);
        when(service.authenticate(ID,RAW)).thenReturn(DEVICE);
        BusinessException failure=assertThrows(BusinessException.class,()->filter.doFilter(request,new MockHttpServletResponse(),
                (incoming,response)->{throw new BusinessException(ErrorCode.FORBIDDEN);}));
        assertEquals(ErrorCode.FORBIDDEN,failure.errorCode()); assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static MockHttpServletRequest request(String path) { return new MockHttpServletRequest("POST",path); }
    private static void headers(MockHttpServletRequest request) { request.addHeader("X-Device-Id",ID); request.addHeader("X-Device-Token",RAW); }
}
