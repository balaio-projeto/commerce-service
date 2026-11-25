package br.com.balaio.commerce.promotion.application;

import java.util.Map;

public class CupomInvalido extends RuntimeException {
    private final Map<String, String> campos;

    public CupomInvalido(Map<String, String> campos) {
        super("Confira os campos destacados do cupom.");
        this.campos = Map.copyOf(campos);
    }

    public Map<String, String> campos() {
        return campos;
    }
}
