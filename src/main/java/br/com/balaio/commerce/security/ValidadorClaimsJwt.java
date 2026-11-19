package br.com.balaio.commerce.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates the signed JSON itself so claim coercion cannot relax the wire contract. */
final class ValidadorClaimsJwt implements OAuth2TokenValidator<Jwt> {
    private static final Set<String> ROLES = Set.of("comprador", "artesao", "organizador", "admin");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Sessão inválida.", null));
    private final String issuer;
    private final String expectedAudience;
    private final Clock clock;

    ValidadorClaimsJwt(String issuer, String audience, Clock clock) {
        this.issuer = issuer;
        this.expectedAudience = audience;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String[] parts = jwt.getTokenValue().split("\\.", -1);
            if (parts.length != 3 || parts[2].isEmpty()) {
                return INVALID;
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!header.isObject() || !claims.isObject() || !"RS256".equals(text(header, "alg"))
                    || header.has("jwk") || header.has("jku") || header.has("x5u") || header.has("x5c")
                    || !canonicalUuid(text(claims, "sub")) || !ROLES.contains(text(claims, "papel"))
                    || !issuer.equals(text(claims, "iss"))
                    || !validAudience(claims.get("aud")) || text(claims, "jti").isBlank()) {
                return INVALID;
            }
            Instant issuedAt = numericDate(claims.get("iat"));
            Instant expiresAt = numericDate(claims.get("exp"));
            Instant now = clock.instant();
            if (issuedAt.isAfter(now.plusSeconds(30)) || !expiresAt.isAfter(now)
                    || !expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(3600))
                    || (claims.has("nbf") && numericDate(claims.get("nbf")).isAfter(now))) {
                return INVALID;
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return INVALID;
        }
    }

    private boolean validAudience(JsonNode audience) {
        if (audience == null) {
            return false;
        }
        if (audience.isString()) {
            return expectedAudience.equals(audience.stringValue());
        }
        if (!audience.isArray() || audience.isEmpty()) {
            return false;
        }
        boolean matches = false;
        for (JsonNode value : audience) {
            if (!value.isString()) {
                return false;
            }
            matches |= expectedAudience.equals(value.stringValue());
        }
        return matches;
    }

    private static Instant numericDate(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new IllegalArgumentException("NumericDate inválido.");
        }
        return Instant.ofEpochSecond(node.longValue());
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isString() ? value.stringValue() : "";
    }

    private static boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
