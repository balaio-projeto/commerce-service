package br.com.balaio.commerce.shared.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

public final class PapelJwt {
    private PapelJwt() { }

    public static UUID exigir(Jwt jwt, String papel) {
        if (jwt == null || !papel.equals(jwt.getClaimAsString("papel")))
            throw new AcessoNegado("Operação não permitida.");
        return UUID.fromString(jwt.getSubject());
    }
}
