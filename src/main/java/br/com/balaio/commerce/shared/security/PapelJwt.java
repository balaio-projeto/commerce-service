package br.com.balaio.commerce.shared.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

public final class PapelJwt {
    private static final Set<String> PAPEIS_QUE_COMPRAM = Set.of("comprador", "artesao");

    private PapelJwt() { }

    public static UUID exigir(Jwt jwt, String papel) {
        return exigirUmDe(jwt, Set.of(papel));
    }

    public static UUID exigirQuemCompra(Jwt jwt) {
        return exigirUmDe(jwt, PAPEIS_QUE_COMPRAM);
    }

    private static UUID exigirUmDe(Jwt jwt, Set<String> papeis) {
        if (jwt == null || !papeis.contains(String.valueOf(jwt.getClaimAsString("papel"))))
            throw new AcessoNegado("Operação não permitida.");
        return UUID.fromString(jwt.getSubject());
    }
}
