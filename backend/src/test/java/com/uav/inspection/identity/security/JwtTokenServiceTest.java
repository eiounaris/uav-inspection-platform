package com.uav.inspection.identity.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenServiceTest {
    private static final String SECRET = "f16a98c3b72d0e4593a6c48d0527f9be8ac65143e27db06f954c8ae170b32d69";
    private static final String OTHER_SECRET = "e16a98c3b72d0e4593a6c48d0527f9be8ac65143e27db06f954c8ae170b32d69";
    private static final String USER_ID = "10000000-0000-4000-8000-000000000005";
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private final JwtTokenService service = serviceAt(NOW);

    @Test
    void issuesHs256AccessTokenWithOnlyIdentityVersionAndRequiredRegisteredClaims() {
        var issued = service.issue(currentUser());
        var jwt = service.decoder().decode(issued.accessToken());

        assertEquals("HS256", jwt.getHeaders().get("alg"));
        assertEquals("JWT", jwt.getHeaders().get("typ"));
        assertEquals(Set.of("sub", "tokenVersion", "iss", "aud", "iat", "exp"), jwt.getClaims().keySet());
        assertEquals(USER_ID, jwt.getSubject());
        assertEquals(7L, ((Number) jwt.getClaims().get("tokenVersion")).longValue());
        assertEquals("uav-inspection", jwt.getClaims().get("iss"));
        assertEquals(List.of("uav-inspection-api"), jwt.getAudience());
        assertEquals(NOW, jwt.getIssuedAt());
        assertEquals(NOW.plus(LIFETIME), jwt.getExpiresAt());
        assertEquals(jwt.getExpiresAt(), issued.expiresAt());
    }

    @Test
    void responseExpiryMatchesSignedSecondPrecisionWhenClockHasFractionalSeconds() {
        var fractionalClockService = serviceAt(NOW.plusMillis(567));
        var issued = fractionalClockService.issue(currentUser());
        var jwt = fractionalClockService.decoder().decode(issued.accessToken());
        assertEquals(jwt.getExpiresAt(), issued.expiresAt());
        assertEquals(LIFETIME, Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
    }

    @Test
    void rejectsModifiedSignatureAndTokensSignedByAnotherKey() {
        String token = service.issue(currentUser()).accessToken();
        String[] segments = token.split("\\.");
        String changedSignature = (segments[2].charAt(0) == 'A' ? "B" : "A") + segments[2].substring(1);
        String tampered = segments[0] + "." + segments[1] + "." + changedSignature;
        assertThrows(JwtException.class, () -> service.decoder().decode(tampered));

        var other = new JwtTokenService(environment(OTHER_SECRET), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(JwtException.class, () -> other.decoder().decode(token));
    }

    @Test
    void acceptsJustBeforeExpiryAndRejectsAtTheExactExpiryInstant() {
        var issued = service.issue(currentUser());
        assertDoesNotThrow(() -> serviceAt(issued.expiresAt().minusNanos(1)).decoder().decode(issued.accessToken()));
        assertThrows(JwtException.class,
                () -> serviceAt(issued.expiresAt()).decoder().decode(issued.accessToken()));
        assertThrows(JwtException.class,
                () -> serviceAt(issued.expiresAt().plusSeconds(1)).decoder().decode(issued.accessToken()));
    }

    @Test
    void rejectsFutureIssuedAtAndLifetimesLongerThanThirtyMinutes() throws Exception {
        reject(claims -> claims.put("iat", Date.from(NOW.plusSeconds(1))));
        reject(claims -> claims.put("exp", Date.from(NOW.plus(LIFETIME).plusSeconds(1))));
    }

    @Test
    void rejectsExpirationEqualToOrBeforeIssuedAt() throws Exception {
        reject(claims -> claims.put("exp", Date.from(NOW)));
        reject(claims -> claims.put("exp", Date.from(NOW.minusSeconds(1))));
    }

    @Test
    void rejectsWrongOrAbsentIssuerAndAudience() throws Exception {
        reject(claims -> claims.put("iss", "another-issuer"));
        reject(claims -> claims.put("aud", List.of("another-api")));
        reject(claims -> claims.remove("iss"));
        reject(claims -> claims.remove("aud"));
    }

    @Test
    void rejectsHs384AndUnsignedTokens() throws Exception {
        byte[] longerKey = new byte[64];
        System.arraycopy(HexFormat.of().parseHex(SECRET), 0, longerKey, 0, 32);
        System.arraycopy(HexFormat.of().parseHex(OTHER_SECRET), 0, longerKey, 32, 32);
        var hs384 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS384).type(JOSEObjectType.JWT).build(),
                claims(ignored -> {}));
        hs384.sign(new MACSigner(longerKey));
        assertThrows(JwtException.class, () -> service.decoder().decode(hs384.serialize()));
        String unsigned = new PlainJWT(claims(ignored -> {})).serialize();
        assertThrows(JwtException.class, () -> service.decoder().decode(unsigned));
    }

    @Test
    void rejectsMissingRequiredIdentityAndTimestampClaims() throws Exception {
        for (String claim : List.of("sub", "tokenVersion", "exp", "iat")) {
            reject(claims -> claims.remove(claim));
        }
    }

    @Test
    void rejectsNonUuidOrNonStringSubjects() throws Exception {
        reject(claims -> claims.put("sub", "not-a-user-id"));
        reject(claims -> claims.put("sub", "1-1-1-1-1"));
        reject(claims -> claims.put("sub", 12345));
    }

    @Test
    void rejectsNegativeFractionalAndStringTokenVersions() throws Exception {
        reject(claims -> claims.put("tokenVersion", -1));
        reject(claims -> claims.put("tokenVersion", 1.5));
        reject(claims -> claims.put("tokenVersion", "7"));
        reject(claims -> claims.put("tokenVersion", true));
    }

    @Test
    void rejectsWrongOrMissingTokenTypeEvenWithAValidSignature() throws Exception {
        var wrongType = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType("at+jwt")).build(), claims(ignored -> {}));
        wrongType.sign(new MACSigner(HexFormat.of().parseHex(SECRET)));
        assertThrows(JwtException.class, () -> service.decoder().decode(wrongType.serialize()));
        var absentType = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims(ignored -> {}));
        absentType.sign(new MACSigner(HexFormat.of().parseHex(SECRET)));
        assertThrows(JwtException.class, () -> service.decoder().decode(absentType.serialize()));
    }

    @Test
    void rejectsMissingBlankShortUppercaseAndNonHexSecretAtConstruction() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenService(new MockEnvironment(), Clock.fixed(NOW, ZoneOffset.UTC)));
        for (String invalid : List.of("", " ", "0123456789abcdef", SECRET.toUpperCase(), "g".repeat(64))) {
            assertThrows(IllegalStateException.class,
                    () -> new JwtTokenService(environment(invalid), Clock.fixed(NOW, ZoneOffset.UTC)));
        }
    }

    @Test
    void supportsTheExplicitEnvironmentVariableFallbackWithoutCreatingADefaultKey() {
        var fromVariable = new JwtTokenService(new MockEnvironment().withProperty("JWT_SECRET", SECRET),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var issued = fromVariable.issue(currentUser());
        assertDoesNotThrow(() -> service.decoder().decode(issued.accessToken()));
    }

    private void reject(Consumer<Map<String, Object>> change) throws Exception {
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims(change));
        signed.sign(new MACSigner(HexFormat.of().parseHex(SECRET)));
        String encoded = signed.serialize();
        assertThrows(JwtException.class, () -> service.decoder().decode(encoded));
    }

    private static JWTClaimsSet claims(Consumer<Map<String, Object>> change) {
        var values = new LinkedHashMap<String, Object>();
        values.put("sub", USER_ID);
        values.put("tokenVersion", 7L);
        values.put("iss", "uav-inspection");
        values.put("aud", List.of("uav-inspection-api"));
        values.put("iat", Date.from(NOW));
        values.put("exp", Date.from(NOW.plus(LIFETIME)));
        change.accept(values);
        var builder = new JWTClaimsSet.Builder();
        values.forEach(builder::claim);
        return builder.build();
    }

    private static CurrentUser currentUser() {
        return new CurrentUser(USER_ID, "00000000-0000-4000-8000-000000000002", "operator-user",
                List.of(Role.OPERATOR_USER), 7, 0);
    }

    private static JwtTokenService serviceAt(Instant instant) {
        return new JwtTokenService(environment(SECRET), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static MockEnvironment environment(String secret) {
        return new MockEnvironment().withProperty("inspection.auth.jwt-secret", secret);
    }
}
