package br.com.balaio.commerce.payment.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;

public interface PagamentoCheckoutUseCase {
    void exigirDisponivel();
    void preparar(UUID pedidoId);
    void tentarOperacao(UUID pedidoId);
    EstadoPagamento consultarPagamento(UUID pedidoId, String estadoPedido, BigDecimal total);

    record EstadoPagamento(String estado, String checkoutUrl) { }
}
