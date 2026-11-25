package br.com.balaio.commerce.shipping.application.port.out;

import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.shipping.domain.OrigemAtelie;

public interface OrigemFreteAtelie {
    Optional<OrigemAtelie> origem(UUID artesao);

    default Optional<String> cepOrigem(UUID artesao) {
        return origem(artesao).flatMap(OrigemAtelie::cepNormalizado);
    }
}
