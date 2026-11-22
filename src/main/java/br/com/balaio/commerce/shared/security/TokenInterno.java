package br.com.balaio.commerce.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenInterno {
    private final byte[] segredo;
    private final boolean configurado;

    public TokenInterno(@Value("${balaio.internal-token:}") String segredo) {
        this.segredo = segredo.getBytes(StandardCharsets.UTF_8);
        this.configurado = segredo.length() >= 32;
    }

    public void exigir(String recebido) {
        if (!configurado || recebido == null
                || !MessageDigest.isEqual(segredo, recebido.getBytes(StandardCharsets.UTF_8)))
            throw new AcessoNegado("Operação interna não permitida.");
    }
}
