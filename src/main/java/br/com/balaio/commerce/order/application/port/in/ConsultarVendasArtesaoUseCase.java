package br.com.balaio.commerce.order.application.port.in;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Pendente;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores.PerfilComprador;

public interface ConsultarVendasArtesaoUseCase {
    List<Venda> vendas(UUID artesao);
    List<PendenteComComprador> pendentes(UUID artesao);
    Venda venda(UUID id, UUID artesao);

    record Venda(Resumo pedido, PerfilComprador comprador, Object enderecoEntrega) {
        public Venda(Resumo pedido, PerfilComprador comprador) {
            this(pedido, comprador, null);
        }
    }
    record PendenteComComprador(Pendente pendente, PerfilComprador comprador) { }
}
