package br.com.balaio.commerce.order.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class RegrasEnvio {
    public static final int TAMANHO_MAXIMO_TRANSPORTADORA = 60;
    private static final Pattern RASTREIO = Pattern.compile("[A-Z0-9][A-Z0-9-]{4,39}");

    private RegrasEnvio() { }

    public static Optional<String> rastreioNormalizado(String rastreio) {
        String codigo = rastreio == null ? "" : rastreio.trim().toUpperCase(Locale.ROOT);
        return RASTREIO.matcher(codigo).matches() ? Optional.of(codigo) : Optional.empty();
    }

    public static String transportadoraNormalizada(String transportadora) {
        return transportadora == null || transportadora.isBlank() ? null : transportadora.trim();
    }

    public static boolean transportadoraValida(String transportadora) {
        return transportadora == null || transportadora.length() <= TAMANHO_MAXIMO_TRANSPORTADORA;
    }
}
