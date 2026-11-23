package br.com.balaio.commerce.order.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface EnderecoSalvo {
    Map<String, String> consultar(UUID id, String tokenComprador);
}
