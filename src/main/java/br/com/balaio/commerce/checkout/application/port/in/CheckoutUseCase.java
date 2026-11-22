package br.com.balaio.commerce.checkout.application.port.in;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Detalhe;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase.EstadoPagamento;

public interface CheckoutUseCase {
    DetalheComPagamento checkout(Checkout comando, UUID comprador, String chave, String tokenComprador);
    DetalheComPagamento consultar(UUID id, UUID comprador);
    List<ResumoComPagamento> listar(UUID comprador);
    DetalheComPagamento cancelar(UUID id, UUID comprador);
    Resumo recusar(UUID id, UUID artesao, String motivo);
    Resumo mediacao(UUID id, UUID decisaoId, String decisao);

    record ResumoComPagamento(Resumo pedido, EstadoPagamento pagamento) { }
    record DetalheComPagamento(Detalhe detalhe, EstadoPagamento pagamento) { }
}
