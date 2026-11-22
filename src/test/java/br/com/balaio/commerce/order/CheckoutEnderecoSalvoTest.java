package br.com.balaio.commerce.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.commerce.checkout.application.CheckoutService;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.order.application.port.in.ComandosPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Detalhe;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase;
import org.junit.jupiter.api.Test;

class CheckoutEnderecoSalvoTest {
    @Test
    void checkoutEncaminhaTokenDoCompradorApenasParaEnderecoSalvo() {
        var pedidos = mock(CheckoutPedidoUseCase.class);
        var consultas = mock(ConsultarPedidosUseCase.class);
        var processamento = mock(ProcessarPedidosPendentesUseCase.class);
        var pagamentos = mock(PagamentoCheckoutUseCase.class);
        var checkout = new CheckoutService(pedidos, consultas, mock(ComandosPedidoUseCase.class),
                processamento, pagamentos);
        UUID comprador = UUID.randomUUID();
        UUID enderecoId = UUID.randomUUID();
        UUID pedidoId = UUID.randomUUID();
        var comando = new Checkout(List.of(new Item("vaso", 1)), "padrao", "checkout_pro", Map.of(), enderecoId);
        var resumo = new Resumo(pedidoId, comprador, UUID.randomUUID(), "vaso", OffsetDateTime.now(),
                "aguardando_pagamento", BigDecimal.TEN, null, false, List.of(), List.of());
        when(pedidos.checkoutSalvo(comando, comprador, "chave", "jwt-comprador")).thenReturn(pedidoId);
        when(consultas.consultar(pedidoId, comprador)).thenReturn(new Detalhe(resumo, Map.of()));

        checkout.checkout(comando, comprador, "chave", "jwt-comprador");

        var ordem = inOrder(pedidos, consultas, processamento, pagamentos);
        ordem.verify(pagamentos).exigirDisponivel();
        ordem.verify(pedidos).checkoutSalvo(comando, comprador, "chave", "jwt-comprador");
        ordem.verify(processamento).processar(pedidoId);
        ordem.verify(pagamentos).preparar(pedidoId);
        ordem.verify(consultas).consultar(pedidoId, comprador);
        ordem.verify(pagamentos).consultarPagamento(pedidoId, "aguardando_pagamento", BigDecimal.TEN);
        verify(pedidos, never()).checkout(any(), any(), any());
    }
}
