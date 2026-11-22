package br.com.balaio.commerce.checkout.application;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.ComandosPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase.EstadoPagamento;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService implements CheckoutUseCase {
    private final CheckoutPedidoUseCase checkout;
    private final ConsultarPedidosUseCase consultas;
    private final ComandosPedidoUseCase comandos;
    private final ProcessarPedidosPendentesUseCase processamento;
    private final PagamentoCheckoutUseCase pagamentos;

    public CheckoutService(CheckoutPedidoUseCase checkout, ConsultarPedidosUseCase consultas, ComandosPedidoUseCase comandos,
            ProcessarPedidosPendentesUseCase processamento, PagamentoCheckoutUseCase pagamentos) {
        this.checkout = checkout;
        this.consultas = consultas;
        this.comandos = comandos;
        this.processamento = processamento;
        this.pagamentos = pagamentos;
    }

    @Override
    public DetalheComPagamento checkout(Checkout comando, UUID comprador, String chave, String tokenComprador) {
        pagamentos.exigirDisponivel();
        UUID id = comando != null && comando.enderecoId() != null
                ? checkout.checkoutSalvo(comando, comprador, chave, tokenComprador)
                : checkout.checkout(comando, comprador, chave);
        processamento.processar(id);
        pagamentos.preparar(id);
        return consultar(id, comprador);
    }

    @Override
    public DetalheComPagamento consultar(UUID id, UUID comprador) {
        var detalhe = consultas.consultar(id, comprador);
        return new DetalheComPagamento(detalhe, pagamento(detalhe.pedido()));
    }

    @Override
    public List<ResumoComPagamento> listar(UUID comprador) {
        return consultas.listar(comprador).stream()
                .map(pedido -> new ResumoComPagamento(pedido, pagamento(pedido))).toList();
    }

    @Override
    public DetalheComPagamento cancelar(UUID id, UUID comprador) {
        comandos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador antes da produção.");
        processarEncerramento(id);
        return consultar(id, comprador);
    }

    @Override
    public Resumo recusar(UUID id, UUID artesao, String motivo) {
        comandos.encerrar(id, artesao, "recusado", motivo);
        processarEncerramento(id);
        return consultas.consultarArtesao(id, artesao);
    }

    @Override
    public Resumo mediacao(UUID id, UUID decisaoId, String decisao) {
        comandos.mediacao(id, decisaoId, decisao);
        processarEncerramento(id);
        return consultas.consultarInterno(id);
    }

    private void processarEncerramento(UUID id) {
        processamento.processar(id);
        pagamentos.tentarOperacao(id);
    }

    private EstadoPagamento pagamento(Resumo pedido) {
        return pagamentos.consultarPagamento(pedido.id(), pedido.estado(), pedido.total());
    }
}
