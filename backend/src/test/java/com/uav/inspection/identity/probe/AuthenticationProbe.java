package com.uav.inspection.identity.probe;

import com.uav.inspection.UavInspectionApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit real HTTP/MySQL probe, never a JUnit test or part of the executable application JAR. */
public final class AuthenticationProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String API = "/api/v1";
    private static final Map<String, String> TABLE_ORDER = new LinkedHashMap<>();
    private static final Set<String> USER_FIELDS = Set.of(
            "id", "orgId", "username", "roles", "tokenVersion", "lockVersion");
    static {
        TABLE_ORDER.put("organization", "id");
        TABLE_ORDER.put("app_user", "id");
        TABLE_ORDER.put("inspection_task", "id");
        TABLE_ORDER.put("task_member", "task_id, user_id");
        TABLE_ORDER.put("drone", "id");
    }

    private final Path reportPath;
    private final String runId = UUID.randomUUID().toString();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<String> secrets = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private HttpClient client;
    private String baseUrl;

    private AuthenticationProbe(Path reportPath) {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        report.put("formatVersion", 1);
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("passed", false);
        report.put("checks", checks);
        report.put("requests", requests);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: AuthenticationProbe <reportPath>");
            System.exit(2);
            return;
        }
        var probe = new AuthenticationProbe(Path.of(args[0]));
        try {
            probe.execute();
            probe.writeReport();
            System.out.println("[PASS] T008 real HTTP/MySQL authentication probe: " + probe.reportPath);
        } catch (Exception failure) {
            probe.report.put("passed", false);
            probe.report.put("failureType", failure.getClass().getSimpleName());
            if (failure instanceof ProbeFailure) {
                probe.report.put("failedCheck", failure.getMessage());
            }
            try {
                probe.writeReport();
            } catch (Exception ignored) {
                System.err.println("Could not write the authentication probe failure report.");
            }
            // HTTP/SQL/JSON exceptions may contain credentials. Never print their messages or causes.
            System.err.println("[FAIL] T008 authentication probe (" + failure.getClass().getSimpleName()
                    + "); inspect the safe report metadata.");
            System.exit(1);
        }
    }

    private void execute() throws Exception {
        report.put("stage", "application_startup");
        try (var context = new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.SERVLET).profiles("local", "database").logStartupInfo(false)
                .run("--server.port=0", "--logging.level.com.uav.inspection=INFO")) {
            require(context instanceof ServletWebServerApplicationContext, "real_servlet_context_required");
            require(!List.of(context.getEnvironment().getActiveProfiles()).contains("demo"),
                    "probe_must_not_run_demo_initializer");
            jdbc = context.getBean(JdbcTemplate.class);
            dataSource = context.getBean(DataSource.class);
            baseUrl = "http://127.0.0.1:"
                    + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            report.put("realHttp", true);
            try (Connection connection = dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()),
                        "real_mysql_required");
            }
            byte[] baseline = businessSnapshot();
            report.put("businessCountsBefore", businessCounts());
            try (HttpClient actualClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client = actualClient;
                try {
                    report.put("stage", "fixture_creation");
                    createFixtures();
                    report.put("stage", "http_authentication_and_authorization");
                    verifyAuthentication();
                    report.put("stage", "audit_safety");
                    verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted", true);
                    cleanupFixtures();
                    require(MessageDigest.isEqual(baseline, businessSnapshot()),
                            "original_business_data_changed");
                    report.put("originalBusinessDataUnchanged", true);
                    report.put("businessCountsAfter", businessCounts());
                }
            }
        }
        report.put("stage", "complete");
        report.put("completedAt", Instant.now().toString());
        report.put("passed", true);
    }

    private void createFixtures() {
        fixture(OWNER, "owner-admin", List.of("ORG_ADMIN", "OWNER_TASK_ADMIN"), true);
        fixture(OWNER, "owner-reviewer", List.of("OWNER_REVIEWER"), true);
        fixture(OWNER, "owner-target", List.of("OWNER_REVIEWER"), true);
        fixture(OPERATOR, "operator-admin", List.of("ORG_ADMIN", "OPERATOR_TASK_ADMIN"), true);
        fixture(OPERATOR, "operator-target", List.of("OPERATOR_USER"), true);
        fixture(OWNER, "disabled", List.of("OWNER_REVIEWER"), false);
        report.put("fixtureIds", fixtures.stream().map(Fixture::id).toList());
    }

    private void fixture(String orgId, String suffix, List<String> roles, boolean enabled) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        secrets.add(password);
        var fixture = new Fixture(UUID.randomUUID().toString(), orgId,
                "t008-" + runId.substring(0, 12) + "-" + suffix, password, roles);
        fixtures.add(fixture);
        String encoded = new BCryptPasswordEncoder(12).encode(password);
        require(jdbc.update("""
                INSERT INTO app_user (id,org_id,username,password_hash,roles,enabled)
                VALUES (?,?,?,?,CAST(? AS JSON),?)
                """, fixture.id(), orgId, fixture.username(), encoded,
                JSON.writeValueAsString(roles), enabled) == 1, "fixture_inserted");
    }

    private void verifyAuthentication() throws Exception {
        Fixture adminA = fixtures.get(0), reviewer = fixtures.get(1), targetA = fixtures.get(2);
        Fixture adminB = fixtures.get(3), targetB = fixtures.get(4), disabled = fixtures.get(5);
        expect("anonymous_me", "GET", API + "/users/me", null, null, 401);
        expect("anonymous_logout", "POST", API + "/auth/logout", null, null, 401);
        expect("anonymous_roles", "PUT", rolesPath(targetA), null,
                roles(List.of("OWNER_REVIEWER"), 0), 401);

        Reply missing = expect("unknown_username", "POST", API + "/auth/login", null,
                Map.of("username", "t008-missing-" + runId, "password", reviewer.password()), 401);
        Reply bad = expect("bad_password", "POST", API + "/auth/login", null,
                Map.of("username", reviewer.username(), "password", reviewer.password() + "x"), 401);
        Reply inactive = expect("disabled_login", "POST", API + "/auth/login", null,
                credentials(disabled), 401);
        require(missing.body().get("code").equals(bad.body().get("code"))
                        && missing.body().get("message").equals(bad.body().get("message"))
                        && missing.body().get("message").equals(inactive.body().get("message"))
                        && missing.body().get("data") == null && bad.body().get("data") == null
                        && inactive.body().get("data") == null,
                "unknown_bad_password_and_disabled_have_uniform_errors");
        for (String field : List.of("orgId", "roles", "tokenVersion")) {
            var supplied = new LinkedHashMap<String, Object>(credentials(adminA));
            supplied.put(field, field.equals("roles") ? List.of("ORG_ADMIN") : field.equals("orgId") ? OPERATOR : 99);
            expect("login_unknown_field_" + field, "POST", API + "/auth/login", null, supplied, 400);
        }

        String tokenA = login(adminA, "login_owner_admin");
        String tokenB = login(adminB, "login_operator_admin");
        String tokenReviewer = login(reviewer, "login_owner_reviewer");
        String oldTargetA = login(targetA, "login_owner_target_before_roles");
        verifyMe(adminA, tokenA, adminA.roles(), 0, 0, "owner_me");
        verifyMe(adminB, tokenB, adminB.roles(), 0, 0, "operator_me");
        verifyMe(targetA, oldTargetA, targetA.roles(), 0, 0, "target_me_before_roles");
        expect("bad_signature", "GET", API + "/users/me", tamperSignature(tokenA), null, 401);

        Reply ordinary = changeRoles("reviewer_cannot_manage_roles", targetA, tokenReviewer,
                List.of("OWNER_DATA_APPROVER"), 0, 403);
        noRoleAudit(ordinary);
        Reply cross = changeRoles("owner_cannot_manage_operator", targetB, tokenA,
                List.of("OPERATOR_TASK_ADMIN"), 0, 403);
        noRoleAudit(cross);
        Reply nonexistent = expect("unknown_target_forbidden", "PUT", API + "/users/"
                + UUID.randomUUID() + "/roles", tokenA, roles(List.of("OWNER_REVIEWER"), 0), 403);
        noRoleAudit(nonexistent);
        for (var invalid : List.of(List.of("OPERATOR_USER"), List.of("UNKNOWN"),
                List.of("OWNER_REVIEWER", "OWNER_REVIEWER"), List.<String>of())) {
            Reply rejected = changeRoles("owner_invalid_roles", targetA, tokenA, invalid, 0, 400);
            noRoleAudit(rejected);
        }
        Reply wrongOrg = changeRoles("operator_rejects_owner_role", targetB, tokenB,
                List.of("OWNER_TASK_ADMIN"), 0, 400);
        noRoleAudit(wrongOrg);

        Reply changedA = changeRoles("owner_changes_own_org_roles", targetA, tokenA,
                List.of("OWNER_DATA_APPROVER"), 0, 200);
        verifyUser(changedA.data(), targetA, List.of("OWNER_DATA_APPROVER"), 1, 1,
                "owner_role_change_response");
        verifyDatabaseUser(targetA, List.of("OWNER_DATA_APPROVER"), 1, 1);
        successAudit(changedA, "USER_ROLES_CHANGED");
        expect("role_change_revokes_old_token", "GET", API + "/users/me", oldTargetA, null, 401);
        String freshTarget = login(targetA, "login_owner_target_after_roles");
        verifyMe(targetA, freshTarget, List.of("OWNER_DATA_APPROVER"), 1, 1,
                "target_me_after_roles");

        Reply changedB = changeRoles("operator_changes_own_org_roles", targetB, tokenB,
                List.of("OPERATOR_TASK_ADMIN"), 0, 200);
        verifyUser(changedB.data(), targetB, List.of("OPERATOR_TASK_ADMIN"), 1, 1,
                "operator_role_change_response");
        verifyDatabaseUser(targetB, List.of("OPERATOR_TASK_ADMIN"), 1, 1);
        successAudit(changedB, "USER_ROLES_CHANGED");

        Reply stale = changeRoles("stale_lock_version", targetA, tokenA,
                List.of("OWNER_REVIEWER"), 0, 409);
        noRoleAudit(stale);
        verifyDatabaseUser(targetA, List.of("OWNER_DATA_APPROVER"), 1, 1);
        verifyConcurrentCas(targetA, tokenA);
        expect("concurrent_role_change_revokes_previous_token", "GET", API + "/users/me",
                freshTarget, null, 401);

        String logoutToken = login(targetA, "login_before_logout");
        // No jti claim is needed. A later iat gives two distinct tokens for the same token version.
        long firstIssuedAt = number(readMap(Base64.getUrlDecoder().decode(logoutToken.split("\\.")[1])).get("iat"));
        long waitDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Instant.now().getEpochSecond() <= firstIssuedAt && System.nanoTime() < waitDeadline) {
            Thread.sleep(20);
        }
        String sameVersionToken = login(targetA, "second_login_before_logout");
        require(!logoutToken.equals(sameVersionToken), "logout_checks_two_distinct_same_version_tokens");
        long beforeLogout = databaseUser(targetA).tokenVersion();
        Reply logout = expect("logout", "POST", API + "/auth/logout", logoutToken, null, 200);
        successAudit(logout, "AUTH_LOGOUT");
        require(databaseUser(targetA).tokenVersion() == beforeLogout + 1, "logout_increments_token_version");
        expect("logout_revokes_own_token", "GET", API + "/users/me", logoutToken, null, 401);
        expect("logout_revokes_other_same_version_token", "GET", API + "/users/me",
                sameVersionToken, null, 401);
        expect("already_revoked_logout", "POST", API + "/auth/logout", logoutToken, null, 401);
        String afterLogout = login(targetA, "login_after_logout");
        Row afterLogoutRow = databaseUser(targetA);
        verifyMe(targetA, afterLogout, afterLogoutRow.roles(), afterLogoutRow.tokenVersion(),
                afterLogoutRow.lockVersion(), "me_after_logout_and_login");

        require(jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", reviewer.id()) == 1,
                "fixture_disabled_for_revocation");
        try {
            expect("disabled_account_revokes_existing_token", "GET", API + "/users/me", tokenReviewer, null, 401);
        } finally {
            jdbc.update("UPDATE app_user SET enabled=1 WHERE id=?", reviewer.id());
        }

        Reply demotion = changeRoles("admin_self_demotion", adminA, tokenA, List.of("OWNER_REVIEWER"), 0, 200);
        successAudit(demotion, "USER_ROLES_CHANGED");
        expect("self_demotion_revokes_previous_token", "GET", API + "/users/me", tokenA, null, 401);
        String demoted = login(adminA, "login_after_self_demotion");
        verifyMe(adminA, demoted, List.of("OWNER_REVIEWER"), 1, 1, "self_demotion_me");
        Reply denied = changeRoles("demoted_admin_cannot_manage_roles", targetA, demoted,
                List.of("OWNER_REVIEWER"), afterLogoutRow.lockVersion(), 403);
        noRoleAudit(denied);
        report.put("concurrentCas", true);
        report.put("roleChangeInvalidatesTokens", true);
        report.put("logoutInvalidatesAllSameVersionTokens", true);
        report.put("disabledAccountInvalidatesTokens", true);
    }

    private void verifyConcurrentCas(Fixture target, String token) throws Exception {
        Row before = databaseUser(target);
        long auditBefore = userRoleAuditCount(target);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        CompletableFuture<Reply> first = concurrentChange(target, token, List.of("OWNER_REVIEWER"),
                before.lockVersion(), "parallel_roles_a", start, ready);
        CompletableFuture<Reply> second = concurrentChange(target, token, List.of("OWNER_TASK_ADMIN"),
                before.lockVersion(), "parallel_roles_b", start, ready);
        require(ready.await(10, TimeUnit.SECONDS), "parallel_requests_ready");
        start.countDown();
        Reply a = first.get(30, TimeUnit.SECONDS), b = second.get(30, TimeUnit.SECONDS);
        require(Set.copyOf(List.of(a.status(), b.status())).equals(Set.of(200, 409)), "parallel_cas_has_one_winner");
        Reply success = a.status() == 200 ? a : b, failure = a.status() == 409 ? a : b;
        require("OK".equals(success.body().get("code"))
                        && "VERSION_CONFLICT".equals(failure.body().get("code")), "parallel_response_codes");
        Row after = databaseUser(target);
        require(after.tokenVersion() == before.tokenVersion() + 1
                        && after.lockVersion() == before.lockVersion() + 1,
                "parallel_cas_increments_versions_only_once");
        require(userRoleAuditCount(target) == auditBefore + 1, "parallel_cas_writes_one_audit");
        successAudit(success, "USER_ROLES_CHANGED");
        noRoleAudit(failure);
        report.put("parallelCasVersions", Map.of("beforeTokenVersion", before.tokenVersion(),
                "afterTokenVersion", after.tokenVersion(), "beforeLockVersion", before.lockVersion(),
                "afterLockVersion", after.lockVersion(), "statuses", List.of(a.status(), b.status())));
    }

    private CompletableFuture<Reply> concurrentChange(Fixture target, String token, List<String> roles,
            long version, String name, CountDownLatch start, CountDownLatch ready) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new ProbeFailure("parallel_start_timeout");
                }
                return request(name, "PUT", rolesPath(target), token, roles(roles, version));
            } catch (Exception failure) {
                throw new ProbeFailure("parallel_request_failure");
            }
        });
    }

    private String login(Fixture fixture, String name) throws Exception {
        Instant before = Instant.now();
        Reply reply = expect(name, "POST", API + "/auth/login", null, credentials(fixture), 200);
        Map<String, Object> result = reply.data();
        require(result.keySet().equals(Set.of("accessToken", "expiresAt")), "login_response_has_only_public_fields");
        require(result.get("accessToken") instanceof String, "login_token_is_string");
        String token = (String) result.get("accessToken");
        secrets.add(token);
        Instant expires = Instant.parse((String) result.get("expiresAt"));
        long validity = Duration.between(before, expires).toSeconds();
        require(validity >= 1790 && validity <= 1801, "login_expiry_is_thirty_minutes");
        String[] parts = token.split("\\.");
        require(parts.length == 3, "jwt_has_three_parts");
        Map<String, Object> header = readMap(Base64.getUrlDecoder().decode(parts[0]));
        Map<String, Object> claims = readMap(Base64.getUrlDecoder().decode(parts[1]));
        require("HS256".equals(header.get("alg")), "jwt_uses_hs256");
        require(claims.keySet().equals(Set.of("iss", "aud", "sub", "tokenVersion", "iat", "exp")),
                "jwt_contains_only_identity_and_version_claims");
        require("uav-inspection".equals(claims.get("iss")), "jwt_issuer");
        Object audience = claims.get("aud");
        require("uav-inspection-api".equals(audience)
                        || audience instanceof List<?> list && list.equals(List.of("uav-inspection-api")),
                "jwt_audience");
        require(fixture.id().equals(claims.get("sub")), "jwt_subject");
        require(number(claims.get("tokenVersion")) == databaseUser(fixture).tokenVersion(), "jwt_current_version");
        require(number(claims.get("exp")) - number(claims.get("iat")) == 1800, "jwt_claim_lifetime");
        require(number(claims.get("exp")) == expires.getEpochSecond(), "jwt_expiry_matches_response");
        successAudit(reply, "AUTH_LOGIN");
        return token;
    }

    private void verifyMe(Fixture fixture, String token, List<String> roles, long tv, long lv, String name)
            throws Exception {
        Reply result = expect(name, "GET", API + "/users/me", token, null, 200);
        verifyUser(result.data(), fixture, roles, tv, lv, name);
    }

    private void verifyUser(Map<String, Object> user, Fixture fixture, List<String> roles,
            long tv, long lv, String name) {
        require(user.keySet().equals(USER_FIELDS), name + "_has_only_public_fields");
        require(fixture.id().equals(user.get("id")) && fixture.orgId().equals(user.get("orgId"))
                        && fixture.username().equals(user.get("username")), name + "_identity");
        require(Set.copyOf(castList(user.get("roles"))).equals(Set.copyOf(roles)), name + "_roles");
        require(number(user.get("tokenVersion")) == tv && number(user.get("lockVersion")) == lv,
                name + "_versions");
    }

    private void verifyDatabaseUser(Fixture fixture, List<String> roles, long tv, long lv) {
        Row row = databaseUser(fixture);
        require(Set.copyOf(row.roles()).equals(Set.copyOf(roles))
                        && row.tokenVersion() == tv && row.lockVersion() == lv,
                "database_roles_and_versions_match_response");
    }

    private Row databaseUser(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT CAST(roles AS CHAR) AS roles_json, token_version, lock_version FROM app_user WHERE id=?
                """, (rs, row) -> new Row(JSON.readValue(rs.getString("roles_json"),new TypeReference<List<String>>() {}),
                rs.getLong("token_version"), rs.getLong("lock_version")), fixture.id());
    }

    private Reply changeRoles(String name, Fixture fixture, String token, List<String> roles,
            long version, int status) throws Exception {
        return expect(name, "PUT", rolesPath(fixture), token, roles(roles, version), status);
    }

    private Reply expect(String name, String method, String path, String token, Object body, int status)
            throws Exception {
        Reply result = request(name, method, path, token, body);
        require(result.status() == status, name + "_status_" + status);
        String code = switch (status) {
            case 200 -> "OK";
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 409 -> "VERSION_CONFLICT";
            default -> throw new ProbeFailure("unsupported_expected_status");
        };
        require(code.equals(result.body().get("code")), name + "_code");
        return result;
    }

    private Reply request(String name, String method, String path, String token, Object body) throws Exception {
        String trace = "t008-" + runId + "-" + requestSequence.incrementAndGet();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                .header("X-Trace-Id", trace).header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case", name, "status", response.statusCode(), "traceId", trace));
        require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                name + "_json_content_type");
        Map<String, Object> envelope = readMap(response.body());
        require(envelope.keySet().equals(Set.of("code", "message", "data", "traceId")),
                name + "_uniform_envelope");
        require(trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null))
                        && trace.equals(envelope.get("traceId")), name + "_trace_header_and_body");
        require(envelope.get("message") instanceof String message && !message.isBlank(),
                name + "_public_message");
        return new Reply(response.statusCode(), envelope, trace);
    }

    private void successAudit(Reply reply, String action) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE trace_id=? AND action=? AND result='SUCCESS'
                """, Long.class, reply.trace(), action);
        require(count != null && count == 1, action.toLowerCase() + "_has_one_success_audit");
    }

    private void noRoleAudit(Reply reply) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE trace_id=? AND action='USER_ROLES_CHANGED'
                """, Long.class, reply.trace());
        require(count != null && count == 0, "rejected_role_change_has_no_success_audit");
    }

    private long userRoleAuditCount(Fixture fixture) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action='USER_ROLES_CHANGED' AND resource_id=?
                """, Long.class, fixture.id());
        require(count != null, "role_audit_count_present");
        return count;
    }

    private void verifyAuditSafety() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t008-" + runId + "-%");
        require(!rows.isEmpty(), "probe_success_actions_are_audited");
        for (var row : rows) {
            Fixture actor = fixtures.stream().filter(fixture -> fixture.id().equals(row.get("actor_id")))
                    .findFirst().orElseThrow(() -> new ProbeFailure("audit_actor_is_not_a_probe_fixture"));
            require("USER".equals(row.get("actor_type")) && "APP_USER".equals(row.get("resource_type"))
                            && actor.orgId().equals(row.get("org_id")) && "SUCCESS".equals(row.get("result"))
                            && fixtures.stream().anyMatch(fixture -> fixture.id().equals(row.get("resource_id")))
                            && Set.of("AUTH_LOGIN", "AUTH_LOGOUT", "USER_ROLES_CHANGED").contains(row.get("action")),
                    "audit_has_correct_safe_actor_organization_resource_metadata");
            String serialized = row.values().stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("\n"));
            require(secrets.stream().noneMatch(serialized::contains), "audit_does_not_contain_passwords_or_jwt");
            require(!serialized.contains("$2a$") && !serialized.contains("$2b$")
                            && !serialized.contains("$2y$") && !serialized.contains("Bearer "),
                    "audit_does_not_contain_credential_hashes_or_authorization_headers");
        }
        report.put("auditRowsRetained", rows.size());
        report.put("auditContainsNoCredentials", true);
    }

    private void cleanupFixtures() {
        for (Fixture fixture : fixtures) {
            jdbc.update("DELETE FROM app_user WHERE id=? AND username=?", fixture.id(), fixture.username());
        }
        for (Fixture fixture : fixtures) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Long.class, fixture.id());
            require(count != null && count == 0, "run_fixture_deleted");
        }
        report.put("fixturesDeleted", fixtures.size());
    }

    private Map<String, Long> businessCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : TABLE_ORDER.keySet()) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private byte[] businessSnapshot() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Connection connection = dataSource.getConnection()) {
            for (var table : TABLE_ORDER.entrySet()) {
                // Fixed identifiers only; original table data never leaves process memory.
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT * FROM " + table.getKey() + " ORDER BY " + table.getValue())) {
                    frame(digest, table.getKey());
                    int count = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        digest.update((byte) 1);
                        for (int column = 1; column <= count; column++) {
                            frame(digest, rows.getString(column));
                        }
                    }
                    digest.update((byte) 0);
                }
            }
        }
        return digest.digest();
    }

    private void writeReport() throws Exception {
        report.put("caseCount", requests.size());
        report.put("checkCount", checks.size());
        Files.createDirectories(reportPath.getParent());
        Path temporary = Files.createTempFile(reportPath.getParent(), ".authentication-probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            String text = new String(serialized, StandardCharsets.UTF_8);
            if (secrets.stream().anyMatch(text::contains)) {
                throw new ProbeFailure("report_contains_credential");
            }
            Files.write(temporary, serialized);
            Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void require(boolean condition, String check) {
        checks.add(Map.of("check", check, "passed", condition));
        if (!condition) {
            throw new ProbeFailure(check);
        }
    }

    private static Map<String, Object> readMap(byte[] body) {
        return JSON.readValue(body,new TypeReference<Map<String,Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        if (!(value instanceof List<?>)) {
            throw new ProbeFailure("expected_json_array");
        }
        return (List<String>) value;
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new ProbeFailure("expected_json_number");
        }
        return number.longValue();
    }

    private static Map<String, Object> credentials(Fixture fixture) {
        return Map.of("username", fixture.username(), "password", fixture.password());
    }

    private static Map<String, Object> roles(List<String> roles, long version) {
        return Map.of("roles", roles, "expectedLockVersion", version);
    }

    private static String rolesPath(Fixture fixture) {
        return API + "/users/" + fixture.id() + "/roles";
    }

    private static String tamperSignature(String token) {
        int start = token.lastIndexOf('.') + 1;
        // Change significant bits of the first signature character, avoiding equivalent base64 padding bits.
        char replacement = token.charAt(start) == 'A' ? 'B' : 'A';
        return token.substring(0, start) + replacement + token.substring(start + 1);
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private record Fixture(String id, String orgId, String username, String password, List<String> roles) {
        @Override public String toString() { return "AuthenticationProbeFixture[" + id + "]"; }
    }
    private record Row(List<String> roles, long tokenVersion, long lockVersion) {}
    private record Reply(int status, Map<String, Object> body, String trace) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            if (!(body.get("data") instanceof Map<?, ?>)) {
                throw new ProbeFailure("expected_json_response_data_object");
            }
            return (Map<String, Object>) body.get("data");
        }
        @Override public String toString() { return "AuthenticationProbeReply[" + status + "," + trace + "]"; }
    }
    private static final class ProbeFailure extends RuntimeException {
        private ProbeFailure(String check) { super(check); }
    }
}
