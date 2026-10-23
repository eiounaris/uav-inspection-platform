package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.anchor.AnchorViews.AnchorView;
import com.uav.inspection.blockchain.anchor.persistence.AnchorQueryMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionQueryServiceTest {
    private static final String ID = "90000000-0000-4000-8000-000000000001";
    private static final String OWNER = ResourcePermissionService.OWNER;
    private static final String OPERATOR = ResourcePermissionService.OPERATOR;
    private final IdentityService identities = mock(IdentityService.class);
    private final TaskScopeMapper scopes = mock(TaskScopeMapper.class);
    private final AnchorQueryMapper mapper = mock(AnchorQueryMapper.class);
    private final ResourcePermissionService permissions = new ResourcePermissionService(
            mock(IdentityMapper.class), identities, scopes);
    private final VersionQueryService service = new VersionQueryService(mapper, permissions);

    @Test
    void aLateRevocationPreventsAnyVersionOrCheckpointLookup() {
        CurrentUser actor = user(Role.OWNER_REVIEWER);
        when(identities.authenticate(actor.id(), actor.tokenVersion()))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> service.anchor(actor, ID)).errorCode());
        verifyNoInteractions(mapper, scopes);
    }

    @Test
    void aKnownVersionAndUnknownVersionAreBothHiddenFromNonmembers() {
        CurrentUser actor = user(Role.OWNER_REVIEWER);
        when(identities.authenticate(actor.id(), actor.tokenVersion())).thenReturn(actor);
        when(mapper.version(ID)).thenReturn(row());
        assertForbidden(() -> service.version(actor, ID));
        when(mapper.version(ID)).thenReturn(null);
        assertForbidden(() -> service.anchor(actor, ID));
        verify(mapper, never()).anchor(ID);
    }

    @Test
    void institutionAdminRoleAloneDoesNotGrantVersionVisibilityEvenWhenListedAsMember() {
        CurrentUser actor = user(Role.ORG_ADMIN);
        when(identities.authenticate(actor.id(), actor.tokenVersion())).thenReturn(actor);
        when(mapper.version(ID)).thenReturn(row());
        assertForbidden(() -> service.anchor(actor, ID));
        verifyNoInteractions(scopes);
        verify(mapper, never()).anchor(ID);
    }

    @Test
    void aReviewerMemberCanReadThePersistedUnknownStateWithoutAnyNetworkCall() {
        CurrentUser actor = user(Role.OWNER_REVIEWER);
        when(identities.authenticate(actor.id(), actor.tokenVersion())).thenReturn(actor);
        when(scopes.countMembership("task", actor.id())).thenReturn(1);
        when(mapper.version(ID)).thenReturn(row());
        AnchorView anchor = new AnchorView("submission", ID, "UNKNOWN", null, null, true, 1,
                Instant.parse("2026-10-22T12:00:00Z"), "COMMIT_STATUS_UNAVAILABLE", "a".repeat(64),
                "UNKNOWN", null, null);
        when(mapper.anchor(ID)).thenReturn(anchor);
        var result = service.version(actor, ID);
        assertEquals("UNKNOWN", result.anchor().status());
        assertNull(result.anchor().confirmedTxId());
        assertEquals("a".repeat(64), result.anchor().transactionId());
        assertEquals("PENDING", result.reviewStatus());
    }

    private CurrentUser user(Role role) {
        return new CurrentUser("user", OWNER, "reviewer", List.of(role), 1, 0);
    }

    private AnchorQueryMapper.VersionRow row() {
        return new AnchorQueryMapper.VersionRow(ID, "evidence", 1, "requirement", "task", "subtask", "execution",
                "inspection.png", "image/png", 79, "b".repeat(64), "PENDING", Instant.EPOCH, OWNER, OPERATOR);
    }

    private void assertForbidden(Runnable action) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, action::run).errorCode());
    }
}
