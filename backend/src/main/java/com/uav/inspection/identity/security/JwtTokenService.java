package com.uav.inspection.identity.security;

import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Issues minimal access tokens; account state and permissions must still be loaded from the database. */
@Service
@Profile("database")
public final class JwtTokenService {
    private static final String ISSUER = "uav-inspection";
    private static final String AUDIENCE = "uav-inspection-api";
    private static final String TOKEN_VERSION = "tokenVersion";
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final OAuth2Error INVALID_CLAIMS = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "Invalid access token claims", null);

    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokenService(Environment environment, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        String secret = environment.getProperty("inspection.auth.jwt-secret");
        if (secret == null) {
            secret = environment.getProperty("JWT_SECRET");
        }
        if (secret == null || !secret.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("JWT_SECRET must contain exactly 64 lowercase hexadecimal characters");
        }
        var key = new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256)
                .validateType(true).build();
        var defaults = MappedJwtClaimSetConverter.withDefaults(Map.of());
        nimbus.setClaimSetConverter(claims -> {
            // The default converter invents iat=exp-1s when iat is absent. Reject before that conversion.
            if (!(claims.get(JwtClaimNames.SUB) instanceof String) || claims.get(JwtClaimNames.IAT) == null) {
                throw new BadJwtException("Invalid access token claims");
            }
            return defaults.convert(claims);
        });
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(ISSUER), new JwtAudienceValidator(AUDIENCE), this::validateClaims));
        decoder = nimbus;
    }

    public IssuedToken issue(CurrentUser currentUser) {
        Objects.requireNonNull(currentUser);
        if (!canonicalUuid(currentUser.id()) || currentUser.tokenVersion() < 0) {
            throw new IllegalArgumentException("Invalid access token subject or version");
        }
        // JWT NumericDate values have second precision; the response expiry matches the signed claim exactly.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(LIFETIME);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(currentUser.id()).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim(TOKEN_VERSION, currentUser.tokenVersion()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(accessToken, expiresAt);
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        Object subject = jwt.getClaims().get(JwtClaimNames.SUB);
        Object version = jwt.getClaims().get(TOKEN_VERSION);
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant now = clock.instant();
        boolean integralVersion = version instanceof Long || version instanceof Integer;
        if (!"HS256".equals(jwt.getHeaders().get("alg")) || !"JWT".equals(jwt.getHeaders().get("typ"))
                || !(subject instanceof String userId) || !canonicalUuid(userId)
                || !integralVersion || ((Number) version).longValue() < 0
                || issuedAt == null || expiresAt == null || issuedAt.isAfter(now)
                || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(now)
                || Duration.between(issuedAt, expiresAt).compareTo(LIFETIME) > 0) {
            return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[accessToken=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
