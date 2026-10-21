package com.uav.inspection;

import com.jayway.jsonpath.JsonPath;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(HttpContractTest.ContractProbeController.class)
class HttpContractTest {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String TRACE_MDC_KEY = "traceId";
    private static final String STATUS_PATH = "/api/v1/system/status";
    private static final String PROBE_PATH = "/api/v1/_test";
    private static final String INTERNAL_SECRET = "database-password=private-contract-secret";
    private static final String CONTAINER_SECRET = "private-container-error-message";

    @Autowired
    private MockMvc mockMvc;

    @LocalServerPort
    private int port;

    private Map<String, String> originalMdc;

    @BeforeEach
    void isolateMdc() {
        originalMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreMdc() {
        MDC.clear();
        if (originalMdc != null) {
            MDC.setContextMap(originalMdc);
        }
    }

    @Test
    void reportsRunningApplicationWithUtcTimestamp() throws Exception {
        MvcResult result = mockMvc.perform(get(STATUS_PATH).header(TRACE_HEADER, "status-request_1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.application").value("uav-inspection-backend"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn();

        assertThat(assertTrace(result)).isEqualTo("status-request_1");
        String message = JsonPath.read(body(result), "$.message");
        assertThat(message).isEqualTo("成功");
        String timestamp = JsonPath.read(body(result), "$.data.timestamp");
        assertThat(timestamp).endsWith("Z");
        assertThat(Instant.parse(timestamp)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("validTraceIds")
    void preservesValidTraceInHeaderBodyAndMdc(String supplied) throws Exception {
        MvcResult result = traceRequest(supplied);
        assertThat(assertTrace(result)).isEqualTo(supplied);
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
    }

    static Stream<String> validTraceIds() {
        return Stream.of("a", "TRACE_2026-10-19", "Z".repeat(64));
    }

    @ParameterizedTest
    @MethodSource("invalidTraceIds")
    void replacesInvalidTraceRatherThanEchoingIt(String supplied) throws Exception {
        String generated = assertTrace(traceRequest(supplied));
        assertThat(generated).isNotEqualTo(supplied);
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
    }

    static Stream<String> invalidTraceIds() {
        return Stream.of("", "trace with spaces", "trace/invalid", "中文", "x".repeat(65));
    }

    @Test
    void generatesTraceWhenHeaderIsMissing() throws Exception {
        assertTrace(traceRequest(null));
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
    }

    @Test
    void returnsUniform404ForAnUnknownRoute() throws Exception {
        expectError(get("/api/v1/does-not-exist").accept(MediaType.TEXT_HTML),
                404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void preservesAllowHeaderForUnsupportedMethod() throws Exception {
        MvcResult result = expectError(post(STATUS_PATH), 405, "METHOD_NOT_ALLOWED");
        String allow = result.getResponse().getHeader(HttpHeaders.ALLOW);
        assertThat(allow).isNotBlank();
        assertThat(Arrays.stream(allow.split(",")).map(String::trim).toList()).contains("GET");
    }

    @Test
    void reportsInvalidDtoWithFieldErrors() throws Exception {
        MvcResult result = expectError(post(PROBE_PATH + "/validated")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"),
                400, "INVALID_ARGUMENT");
        Object errors = JsonPath.read(body(result), "$.data.errors");
        assertThat(errors).isNotNull();
        assertThat(errors.toString()).contains("name");
    }

    @Test
    void rejectsMalformedJsonWithoutExposingParserDetails() throws Exception {
        MvcResult result = expectError(post(PROBE_PATH + "/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"private-malformed-payload\","), 400, "INVALID_ARGUMENT");
        assertThat(body(result)).doesNotContain("private-malformed-payload", "HttpMessageNotReadableException",
                "tools.jackson", "stackTrace");
    }

    @Test
    void rejectsUnsupportedMediaTypeWith415() throws Exception {
        expectError(post(PROBE_PATH + "/validated")
                .contentType(MediaType.TEXT_PLAIN).content("plain text"),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        MvcResult result = expectError(get(PROBE_PATH + "/unexpected"), 500, "INTERNAL_ERROR");
        assertThat(body(result)).doesNotContain(INTERNAL_SECRET, "IllegalStateException", "stackTrace");
    }

    @Test
    void mapsBusinessConflictTo409() throws Exception {
        expectError(get(PROBE_PATH + "/conflict"), 409, "VERSION_CONFLICT");
    }

    @Test
    void restoresExistingMdcAfterSuccessfulAndFailedRequests() throws Exception {
        Map<String, String> parentContext = Map.of(TRACE_MDC_KEY, "parent-trace", "existingKey", "keep");
        MDC.setContextMap(parentContext);

        assertThat(assertTrace(traceRequest("child-trace"))).isEqualTo("child-trace");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(parentContext);
        expectError(get(PROBE_PATH + "/conflict").header(TRACE_HEADER, "failed-child"),
                409, "VERSION_CONFLICT");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(parentContext);
    }

    @Test
    void keepsSequentialRequestTracesSeparate() throws Exception {
        assertThat(assertTrace(traceRequest("first-request"))).isEqualTo("first-request");
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
        String generated = assertTrace(traceRequest(null));
        assertThat(generated).isNotEqualTo("first-request");
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
        assertThat(assertTrace(traceRequest("third-request"))).isEqualTo("third-request");
        assertThat(MDC.get(TRACE_MDC_KEY)).isNull();
    }

    @Test
    void rendersRealServletErrorDispatchWithOriginalTraceAndSafeBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + PROBE_PATH + "/container-error"))
                .header(TRACE_HEADER, "real-error-dispatch_1")
                .timeout(Duration.ofSeconds(10)).GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                    .contains(MediaType.APPLICATION_JSON_VALUE);
            String trace = response.headers().firstValue(TRACE_HEADER).orElse(null);
            assertThat(trace).isEqualTo("real-error-dispatch_1");
            assertErrorEnvelope(response.body(), trace, "INVALID_ARGUMENT");
            assertThat(response.body()).doesNotContain(CONTAINER_SECRET, "stackTrace");
        }
    }

    private MvcResult traceRequest(String traceId) throws Exception {
        MockHttpServletRequestBuilder request = get(PROBE_PATH + "/trace");
        if (traceId != null) {
            request.header(TRACE_HEADER, traceId);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        String observedMdc = JsonPath.read(body(result), "$.data.mdcTraceId");
        assertThat(observedMdc).isEqualTo(result.getResponse().getHeader(TRACE_HEADER));
        return result;
    }

    private MvcResult expectError(MockHttpServletRequestBuilder request, int expectedStatus, String code)
            throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertErrorEnvelope(body(result), result.getResponse().getHeader(TRACE_HEADER), code);
        return result;
    }

    private static void assertErrorEnvelope(String body, String traceId, String code) {
        Map<String, Object> envelope = JsonPath.read(body, "$");
        assertThat(envelope).containsKeys("code", "message", "data", "traceId");
        assertThat(envelope.get("code")).isEqualTo(code);
        assertThat(envelope.get("message")).isInstanceOf(String.class);
        assertThat((String) envelope.get("message")).isNotBlank();
        assertTrace(body, traceId);
    }

    private static String assertTrace(MvcResult result) throws Exception {
        return assertTrace(body(result), result.getResponse().getHeader(TRACE_HEADER));
    }

    private static String assertTrace(String body, String headerTrace) {
        assertThat(headerTrace).isNotNull().matches("[A-Za-z0-9_-]{1,64}");
        String bodyTrace = JsonPath.read(body, "$.traceId");
        assertThat(bodyTrace).isEqualTo(headerTrace);
        return headerTrace;
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @TestComponent
    @RestController
    @RequestMapping(PROBE_PATH)
    static class ContractProbeController {

        @GetMapping("/trace")
        ApiResponse<Map<String, String>> trace() {
            return ApiResponse.success(Map.of("mdcTraceId", MDC.get(TRACE_MDC_KEY)));
        }

        @PostMapping(path = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<ValidationInput> validated(@Valid @RequestBody ValidationInput input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException(INTERNAL_SECRET);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        @GetMapping("/container-error")
        void containerError(HttpServletResponse response) throws IOException {
            response.sendError(400, CONTAINER_SECRET);
        }
    }

    public record ValidationInput(@NotBlank String name) {
    }
}
