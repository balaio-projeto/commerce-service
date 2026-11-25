package br.com.balaio.commerce.promotion.domain;

import java.util.Arrays;
import java.util.Optional;

public enum TipoCupom {
    PERCENTUAL("percentual"),
    VALOR_FIXO("valor_fixo"),
    FRETE_GRATIS("frete_gratis");

    private final String codigo;

    TipoCupom(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static Optional<TipoCupom> de(String codigo) {
        return Arrays.stream(values()).filter(tipo -> tipo.codigo.equals(codigo)).findFirst();
    }
}
