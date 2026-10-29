package com.uav.inspection.identity.security;

import com.uav.inspection.common.error.GlobalExceptionHandler;
import com.uav.inspection.common.web.TraceIdFilter;
import com.uav.inspection.config.SecurityConfiguration;
import com.uav.inspection.config.TimeConfiguration;
import com.uav.inspection.device.DeviceAuthenticationService;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.web.IdentityController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises public static resources through the same bearer and device filter chains as the API. */
@WebMvcTest(controllers = IdentityController.class, properties = {
        "inspection.auth.jwt-secret=5eae31fa0182a4531de932fdc421cf61bcdbf7b645ba06ef465f0537bd513cd2"
})
@ActiveProfiles("database")
@Import({SecurityConfiguration.class, TimeConfiguration.class, JwtTokenService.class,
        TraceIdFilter.class, GlobalExceptionHandler.class})
class UiSecurityContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean IdentityService identities;
    @MockitoBean DeviceAuthenticationService devices;

    @Test void anonymousBrowserCanLoadTheStaticEntryAndExternalModule() throws Exception {
        MvcResult page=mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)).andReturn();
        assertBrowserPolicy(page);
        MvcResult script=mvc.perform(get("/ui/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)).andReturn();
        assertThat(script.getResponse().getContentType()).contains("javascript");
        assertBrowserPolicy(script);
        verifyNoInteractions(identities,devices);
    }

    @Test void exposingStaticGetsDoesNotExposeApiReadsOrAnonymousWrites() throws Exception {
        for(String path:new String[]{"/api/v1/users/me","/api/v1/not-a-public-api"})
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,"Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for(String path:new String[]{"/index.html","/ui/app.js"})
            mvc.perform(post(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(identities,devices);
    }

    @Test void missingStaticAssetsRemainPublic404AndCannotBecomeAnApiFallback() throws Exception {
        mvc.perform(get("/ui/missing-resource.js").header("X-Trace-Id","ui-missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("ui-missing-resource"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        verifyNoInteractions(identities,devices);
    }

    @Test void authenticationErrorsAlsoCarryBrowserPolicyWithoutCrossOriginPermission() throws Exception {
        MvcResult error=mvc.perform(get("/api/v1/users/me").header(HttpHeaders.ORIGIN,"https://untrusted.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
        assertBrowserPolicy(error);
        verifyNoInteractions(identities,devices);
    }

    private static void assertBrowserPolicy(MvcResult result){
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self'", "style-src 'self'", "connect-src 'self'", "object-src 'none'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
