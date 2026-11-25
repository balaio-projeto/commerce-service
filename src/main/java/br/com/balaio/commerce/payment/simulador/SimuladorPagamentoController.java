package br.com.balaio.commerce.payment.simulador;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento;
import br.com.balaio.commerce.payment.application.CheckoutProService;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.TokenInterno;

/**
 * Aprova o pagamento de um pedido no provedor simulado e aplica o resultado pelo mesmo caminho do webhook.
 * Existe só com balaio.pagamento.simulador.ativo=true e exige o token interno.
 */
@RestController
@ConditionalOnProperty(name = "balaio.pagamento.simulador.ativo", havingValue = "true")
public class SimuladorPagamentoController {
    private final SimuladorPagamentoGateway simulador;
    private final PedidoParaPagamento pedidos;
    private final CheckoutProService checkout;
    private final TokenInterno tokenInterno;

    public SimuladorPagamentoController(SimuladorPagamentoGateway simulador, PedidoParaPagamento pedidos,
            CheckoutProService checkout, TokenInterno tokenInterno) {
        this.simulador = simulador;
        this.pedidos = pedidos;
        this.checkout = checkout;
        this.tokenInterno = tokenInterno;
    }

    public record Aprovacao(String pagamentoId, String estado) { }

    @PostMapping("/internal/v1/pedidos/{id}/pagamento-simulado")
    public Envelope aprovar(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        var total = pedidos.totalParaCobranca(id);
        if (total == null) throw new IllegalStateException("Pedido sem total para cobrança.");
        String pagamentoId = simulador.aprovar(id, total);
        checkout.aplicarPagamento(pagamentoId);
        return new Envelope(new Aprovacao(pagamentoId, pedidos.estadoParaPagamento(id)), null);
    }
}
