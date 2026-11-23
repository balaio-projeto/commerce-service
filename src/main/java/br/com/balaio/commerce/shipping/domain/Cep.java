package br.com.balaio.commerce.shipping.domain;

import java.util.Optional;

public final class Cep {
    private Cep() { }

    public static Optional<String> normalizado(String cep) {
        String digitos = cep == null ? "" : cep.replaceAll("[\\s.-]", "");
        if (!digitos.matches("[0-9]{8}") || digitos.chars().distinct().count() == 1) return Optional.empty();
        return Optional.of(digitos);
    }
}
