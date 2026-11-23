package br.com.balaio.commerce.order.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase;
import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.ComandosPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.domain.EstadoPedido;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidosController {
    private final CheckoutUseCase checkout;
    private final ConsultarPedidosUseCase consultas;
    private final ComandosPedidoUseCase comandos;

    public PedidosController(CheckoutUseCase checkout, ConsultarPedidosUseCase consultas, ComandosPedidoUseCase comandos) {
        this.checkout = checkout;
        this.consultas = consultas;
        this.comandos = comandos;
    }

    public record Motivo(String motivo) { }
    public record Estado(String estado, String rastreio, String transportadora) {
        public Estado(String estado) {
            this(estado, null, null);
        }
    }
    public record Item(String slug, Integer quantidade) { }
    public record Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco, UUID enderecoId) {
        CheckoutPedidoUseCase.Checkout comando() {
            var itensCheckout = itens == null ? null : itens.stream()
                    .map(item -> item == null ? null : new CheckoutPedidoUseCase.Item(item.slug(), item.quantidade())).toList();
            return new CheckoutPedidoUseCase.Checkout(itensCheckout, freteId, meio, endereco, enderecoId);
        }
    }

    @PostMapping("/api/v1/checkout")
    public ResponseEntity<Envelope> checkout(@RequestBody Checkout corpo,
            @RequestHeader("Idempotency-Key") String chave, @AuthenticationPrincipal Jwt jwt) {
        UUID comprador = PapelJwt.exigir(jwt, "comprador");
        var detalhe = checkout.checkout(corpo == null ? null : corpo.comando(), comprador, chave, jwt.getTokenValue());
        return ResponseEntity.accepted().body(new Envelope(PedidoResponse.detalhe(detalhe), null));
    }

    @GetMapping("/api/v1/pedidos")
    public Envelope listar(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(checkout.listar(PapelJwt.exigir(jwt, "comprador")).stream()
                .map(PedidoResponse::comPagamento).toList(), null);
    }

    @GetMapping("/api/v1/artesao/vendas")
    public Envelope vendasArtesao(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(consultas.listarVendasArtesao(PapelJwt.exigir(jwt, "artesao")).stream()
                .map(PedidoResponse::de).toList(), null);
    }

    @GetMapping("/api/v1/artesao/pedidos-pendentes")
    public Envelope pendentesArtesao(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(consultas.listarPendentesArtesao(PapelJwt.exigir(jwt, "artesao")).stream()
                .map(PedidoResponse.PendenteResponse::de).toList(), null);
    }

    @GetMapping("/api/v1/pedidos/{id}")
    public Envelope consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return new Envelope(PedidoResponse.detalhe(checkout.consultar(id, PapelJwt.exigir(jwt, "comprador"))), null);
    }

    @PatchMapping("/api/v1/pedidos/{id}/cancelar")
    public ResponseEntity<Envelope> cancelar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var detalhe = checkout.cancelar(id, PapelJwt.exigir(jwt, "comprador"));
        return resposta(detalhe.detalhe().pedido().estado(), PedidoResponse.detalhe(detalhe));
    }

    @PatchMapping("/api/v1/pedidos/{id}/recusar")
    public ResponseEntity<Envelope> recusar(@PathVariable UUID id, @RequestBody Motivo comando,
            @AuthenticationPrincipal Jwt jwt) {
        UUID artesao = PapelJwt.exigir(jwt, "artesao");
        var pedido = checkout.recusar(id, artesao, comando == null ? null : comando.motivo());
        return resposta(pedido.estado(), PedidoResponse.de(pedido));
    }

    @PatchMapping("/api/v1/pedidos/{id}/estado")
    public Envelope estado(@PathVariable UUID id, @RequestBody Estado comando, @AuthenticationPrincipal Jwt jwt) {
        UUID artesao = PapelJwt.exigir(jwt, "artesao");
        if (comando == null)
            throw new PedidoException(PedidoException.Tipo.REQUISICAO_INVALIDA, "Estado inválido.");
        return new Envelope(PedidoResponse.de(comandos.avancar(id, artesao, comando.estado(), comando.rastreio(),
                comando.transportadora())), null);
    }

    private ResponseEntity<Envelope> resposta(String estado, Object dados) {
        var corpo = new Envelope(dados, null);
        return EstadoPedido.encerramentoPendente(estado)
                ? ResponseEntity.accepted().body(corpo) : ResponseEntity.ok(corpo);
    }
}
