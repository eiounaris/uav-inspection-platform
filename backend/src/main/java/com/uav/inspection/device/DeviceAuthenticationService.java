package com.uav.inspection.device;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.device.persistence.AuthDeviceMapper;
import com.uav.inspection.device.persistence.AuthDeviceMapper.IdentityRow;
import com.uav.inspection.identity.ResourcePermissionService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Every request reads current identity; no device token or token digest is cached. */
@Service
@Profile("database")
public class DeviceAuthenticationService {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String DIGEST="[0-9a-f]{64}";
    private static final String DUMMY_DIGEST="0".repeat(64);
    private final AuthDeviceMapper devices;

    public DeviceAuthenticationService(AuthDeviceMapper devices) { this.devices=devices; }

    @Transactional(readOnly=true)
    public CurrentDevice authenticate(String id,String rawToken) {
        if(id==null || !id.matches(UUID) || rawToken==null || !rawToken.matches(DIGEST)) unauthorized();
        try {
            IdentityRow row=devices.identityById(id);
            byte[] actual=sha256(rawToken.getBytes(StandardCharsets.UTF_8));
            String expected=row!=null &&validDigest(row.tokenDigest())?row.tokenDigest():DUMMY_DIGEST;
            boolean matches=MessageDigest.isEqual(HexFormat.of().parseHex(expected),actual);
            if(!validIdentity(row,id) || !validDigest(row.tokenDigest()) || !matches) unauthorized();
            return principal(row);
        } catch(DataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Joins the caller's task/execution transaction before taking the device row lock. */
    @Transactional
    public CurrentDevice currentDevice(CurrentDevice supplied,boolean forUpdate) {
        if(supplied==null || !supplied.id().matches(UUID) || !validDigest(supplied.tokenDigest())) unauthorized();
        try {
            IdentityRow row=forUpdate?devices.lockIdentityById(supplied.id()):devices.identityById(supplied.id());
            String expected=row!=null &&validDigest(row.tokenDigest())?row.tokenDigest():DUMMY_DIGEST;
            boolean matches=MessageDigest.isEqual(HexFormat.of().parseHex(expected),HexFormat.of().parseHex(supplied.tokenDigest()));
            if(!validIdentity(row,supplied.id()) || !validDigest(row.tokenDigest()) || !matches
                    || !row.orgId().equals(supplied.orgId()) || !row.code().equals(supplied.code())) unauthorized();
            return principal(row);
        } catch(DataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static boolean validIdentity(IdentityRow row,String id) {
        return row!=null &&row.enabled() &&id.equals(row.id()) &&ResourcePermissionService.OPERATOR.equals(row.orgId())
                &&row.code()!=null &&!row.code().isBlank();
    }
    private static boolean validDigest(String digest) { return digest!=null &&digest.matches(DIGEST); }
    private static CurrentDevice principal(IdentityRow row) { return new CurrentDevice(row.id(),row.orgId(),row.code(),row.tokenDigest()); }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void unauthorized() { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
}
