package br.com.balaio.commerce.shipping.domain;

import java.util.Optional;

public record OrigemAtelie(String cep, boolean aceitaRetirada, boolean aceitaEntregaLocal, String bairro, String cidade) {
    public static OrigemAtelie somenteCep(String cep) {
        return new OrigemAtelie(cep, false, false, null, null);
    }

    public Optional<String> cepNormalizado() {
        return Cep.normalizado(cep);
    }

    public boolean retiradaDisponivel() {
        return aceitaRetirada && preenchido(bairro) && preenchido(cidade);
    }

    private static boolean preenchido(String texto) {
        return texto != null && !texto.isBlank();
    }
}
