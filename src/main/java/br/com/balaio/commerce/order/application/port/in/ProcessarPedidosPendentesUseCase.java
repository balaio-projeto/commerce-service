package br.com.balaio.commerce.order.application.port.in;

import java.util.UUID;

public interface ProcessarPedidosPendentesUseCase {
    void processarPendentes();
    void processar(UUID id);
}
