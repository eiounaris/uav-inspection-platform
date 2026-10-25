package com.uav.inspection.device;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.device.persistence.AuthDeviceMapper;
import com.uav.inspection.device.persistence.AuthDeviceMapper.IdentityRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceAuthenticationServiceTest {
    private static final String ID="20000000-0000-4000-8000-000000000001";
    private static final String ORG="00000000-0000-4000-8000-000000000002";
    private static final String RAW="a".repeat(64);
    private static final String DIGEST=digest(RAW);
    private final AuthDeviceMapper mapper=mock(AuthDeviceMapper.class);
    private final DeviceAuthenticationService service=new DeviceAuthenticationService(mapper);

    @Test void validTokenAdmitsOnlyTheDatabaseIdentityWithoutSerializingOrPrintingDigest() {
        IdentityRow row=row(true,DIGEST);
        when(mapper.identityById(ID)).thenReturn(row);
        CurrentDevice device=service.authenticate(ID,RAW);
        assertEquals(ID,device.id()); assertEquals(ORG,device.orgId()); assertEquals("UAV-DEMO-001",device.code());
        assertEquals(DIGEST,device.tokenDigest());
        String json=JsonMapper.builder().build().writeValueAsString(device);
        assertFalse(json.contains("tokenDigest")); assertFalse(json.contains(DIGEST)); assertFalse(json.contains(RAW));
        assertFalse(device.toString().contains(DIGEST)); assertFalse(row.toString().contains(DIGEST));
        assertFalse(JsonMapper.builder().build().writeValueAsString(row).contains(DIGEST));
    }

    @Test void unknownDisabledWrongOrganizationAndWrongTokenHaveTheSameUnauthorizedResult() {
        assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(ID,RAW));
        when(mapper.identityById(ID)).thenReturn(row(false,DIGEST));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(ID,RAW));
        when(mapper.identityById(ID)).thenReturn(new IdentityRow(ID,"00000000-0000-4000-8000-000000000001","UAV-DEMO-001",DIGEST,true));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(ID,RAW));
        when(mapper.identityById(ID)).thenReturn(row(true,DIGEST));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(ID,"b".repeat(64)));
    }

    @Test void nonCanonicalCredentialFormsNeverReachTheDatabase() {
        for(String token:new String[]{null,""," "+RAW,RAW+" ","A".repeat(64),"a".repeat(63)})
            assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(ID,token));
        for(String id:new String[]{null,"","not-a-uuid",ID.toUpperCase()+"x",ID+" "})
            assertCode(ErrorCode.UNAUTHORIZED,()->service.authenticate(id,RAW));
        verifyNoInteractions(mapper);
    }

    @Test void disablingOrRotatingTheTokenAfterAdmissionRejectsTheOriginalPrincipalUnderLock() {
        CurrentDevice admitted=new CurrentDevice(ID,ORG,"UAV-DEMO-001",DIGEST);
        when(mapper.lockIdentityById(ID)).thenReturn(row(false,DIGEST));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.currentDevice(admitted,true));
        when(mapper.lockIdentityById(ID)).thenReturn(row(true,digest("b".repeat(64))));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.currentDevice(admitted,true));
        verify(mapper,times(2)).lockIdentityById(ID); verify(mapper,never()).identityById(anyString());
    }

    @Test void reauthenticationCannotReplaceTheAdmittedOrganizationOrIdentity() {
        when(mapper.lockIdentityById(ID)).thenReturn(row(true,DIGEST));
        CurrentDevice forged=new CurrentDevice(ID,"00000000-0000-4000-8000-000000000001","UAV-DEMO-001",DIGEST);
        assertCode(ErrorCode.UNAUTHORIZED,()->service.currentDevice(forged,true));
        when(mapper.lockIdentityById(ID)).thenReturn(new IdentityRow("20000000-0000-4000-8000-000000000002",ORG,"UAV-DEMO-001",DIGEST,true));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.currentDevice(new CurrentDevice(ID,ORG,"UAV-DEMO-001",DIGEST),true));
    }

    @Test void readOnlyReauthenticationUsesFreshDatabaseStateInsteadOfTheSuppliedDigestAlone() {
        CurrentDevice original=new CurrentDevice(ID,ORG,"UAV-DEMO-001",DIGEST);
        when(mapper.identityById(ID)).thenReturn(row(true,DIGEST));
        assertEquals(original,service.currentDevice(original,false));
        when(mapper.identityById(ID)).thenReturn(row(false,DIGEST));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.currentDevice(original,false));
        verify(mapper,never()).lockIdentityById(anyString());
    }

    @Test void databaseFailureIsUnavailableAndDoesNotAttachProviderDiagnostics() {
        var failure=new DataAccessResourceFailureException("private provider detail "+RAW);
        when(mapper.identityById(ID)).thenThrow(failure);
        BusinessException error=assertThrows(BusinessException.class,()->service.authenticate(ID,RAW));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode()); assertNull(error.getCause());
        assertFalse(error.toString().contains(RAW));
        when(mapper.lockIdentityById(ID)).thenThrow(failure);
        assertCode(ErrorCode.DEPENDENCY_UNAVAILABLE,()->service.currentDevice(new CurrentDevice(ID,ORG,"UAV-DEMO-001",DIGEST),true));
    }

    private static IdentityRow row(boolean enabled,String digest) { return new IdentityRow(ID,ORG,"UAV-DEMO-001",digest,enabled); }
    private static void assertCode(ErrorCode code,Runnable action) { assertEquals(code,assertThrows(BusinessException.class,action::run).errorCode()); }
    private static String digest(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
