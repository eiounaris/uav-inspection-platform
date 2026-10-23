package com.uav.inspection.identity.security;

import com.uav.inspection.common.error.GlobalExceptionHandler;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceIdFilter;
import com.uav.inspection.config.SecurityConfiguration;
import com.uav.inspection.config.TimeConfiguration;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.web.IdentityController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers the real bearer filter failure handler as well as MVC database error rendering. */
@WebMvcTest(controllers = IdentityController.class, properties = {
        "inspection.auth.jwt-secret=f16a98c3b72d0e4593a6c48d0527f9be8ac65143e27db06f954c8ae170b32d69"
})
@ActiveProfiles("database")
@Import({SecurityConfiguration.class, TimeConfiguration.class, JwtTokenService.class,
        TraceIdFilter.class, GlobalExceptionHandler.class})
class SecurityFailureContractTest {
    private static final String TRACE = "X-Trace-Id";
    private static final String PRIVATE_DIAGNOSTIC =
            "private-database-diagnostic password_hash=$2b$12$test-only sql=SELECT credentials";
    private static final CurrentUser USER = new CurrentUser(
            "10000000-0000-4000-8000-000000000005",
            "00000000-0000-4000-8000-000000000002", "test-operator",
            List.of(Role.OPERATOR_USER), 7, 3);

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenService tokens;

    @MockitoBean
    IdentityService identities;

    @Test
    void rendersBearerDatabaseFailureAs503ThroughTheRealFilterFailureHandler() throws Exception {
        when(identities.authenticate(USER.id(), USER.tokenVersion()))
                .thenThrow(unavailable());
        // Actual Nimbus HS256 signing and decoding run; no test security request postprocessor is used.
        String accessToken = tokens.issue(USER).accessToken();

        MvcResult result = mvc.perform(get("/api/v1/users/me")
                        .header(TRACE, "bearer-database-down")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertSafeError(result, "DEPENDENCY_UNAVAILABLE", "bearer-database-down");
        verify(identities).authenticate(USER.id(), USER.tokenVersion());
        verifyNoMoreInteractions(identities);
    }

    @Test
    void rendersMissingBearerAs401WithAChallengeAndTheSameTraceEnvelope() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/users/me").header(TRACE, "missing-bearer"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertSafeError(result, "UNAUTHORIZED", "missing-bearer");
        verifyNoInteractions(identities);
    }

    @Test
    void rendersLoginDatabaseFailureAs503WithoutLeakingDiagnostics() throws Exception {
        when(identities.login("test-operator", "test-password"))
                .thenThrow(unavailable());

        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .header(TRACE, "login-database-down")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-operator\",\"password\":\"test-password\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertSafeError(result, "DEPENDENCY_UNAVAILABLE", "login-database-down");
        verify(identities).login("test-operator", "test-password");
        verifyNoMoreInteractions(identities);
    }

    @Test
    void rendersLogoutDatabaseFailureAfterSuccessfulBearerAuthenticationAs503() throws Exception {
        when(identities.authenticate(USER.id(), USER.tokenVersion())).thenReturn(USER);
        doThrow(unavailable()).when(identities).logout(USER);
        String accessToken = tokens.issue(USER).accessToken();

        MvcResult result = mvc.perform(post("/api/v1/auth/logout")
                        .header(TRACE, "logout-database-down")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertSafeError(result, "DEPENDENCY_UNAVAILABLE", "logout-database-down");
        verify(identities).authenticate(USER.id(), USER.tokenVersion());
        verify(identities).logout(USER);
        verifyNoMoreInteractions(identities);
    }

    private static void assertSafeError(MvcResult result, String code, String trace) throws Exception {
        content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
        header().string(TRACE, trace).match(result);
        jsonPath("$.code").value(code).match(result);
        jsonPath("$.message").isNotEmpty().match(result);
        jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()).match(result);
        jsonPath("$.traceId").value(trace).match(result);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(PRIVATE_DIAGNOSTIC, "private-database-diagnostic", "password_hash",
                        "DataAccessResourceFailureException", "AuthenticationServiceException", "stackTrace");
    }

    @Test
    void retainsBearerChallengeWhenTheAccountIsRevokedAfterFilterAuthentication() throws Exception {
        when(identities.authenticate(USER.id(), USER.tokenVersion())).thenReturn(USER);
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED)).when(identities).logout(USER);
        MvcResult result = mvc.perform(post("/api/v1/auth/logout")
                        .header(TRACE, "revoked-after-authentication")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(USER).accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();
        assertSafeError(result, "UNAUTHORIZED", "revoked-after-authentication");
    }

    private static DataAccessResourceFailureException unavailable() {
        return new DataAccessResourceFailureException(PRIVATE_DIAGNOSTIC);
    }
}
