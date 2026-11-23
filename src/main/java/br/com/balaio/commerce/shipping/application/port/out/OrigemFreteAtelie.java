package br.com.balaio.commerce.shipping.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface OrigemFreteAtelie {
    Optional<String> cepOrigem(UUID artesao);
}
