package br.com.balaio.commerce.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.commerce.payment.CheckoutProService;

import org.springframework.beans.factory.annotation.Value;
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
    private final PedidoService pedidos;
    private final CheckoutProService checkoutPro;
    private final String tokenInterno;

    public PedidosController(PedidoService pedidos, CheckoutProService checkoutPro,
            @Value("${balaio.internal-token:}") String tokenInterno) {
        this.pedidos = pedidos;
        this.checkoutPro = checkoutPro;
        this.tokenInterno = tokenInterno;
    }

    public record Envelope(Object dados, Object erro) { }
    public record Motivo(String motivo) { }
    public record Estado(String estado) { }
    public record Decisao(UUID decisaoId, String decisao) { }
    public record Frete(String id, String nome, String prazo, BigDecimal valor) { }

    @GetMapping("/api/v1/fretes")
    public Envelope fretes() {
        return new Envelope(List.of(new Frete("padrao", "Entrega regional demonstrativa",
                "10 a 14 dias úteis", new BigDecimal("38.90"))), null);
    }

    @PostMapping("/api/v1/checkout")
    public ResponseEntity<Envelope> checkout(@RequestBody PedidoService.Checkout comando,
            @RequestHeader("Idempotency-Key") String chave, @AuthenticationPrincipal Jwt jwt) {
        UUID comprador = exigirPapel(jwt, "comprador");
        UUID id = pedidos.checkout(comando, comprador, chave);
        pedidos.processar(id);
        checkoutPro.preparar(id);
        return ResponseEntity.accepted().body(new Envelope(checkoutPro.complementar(id, pedidos.consultar(id, comprador)), null));
    }

    @GetMapping("/api/v1/pedidos")
    public Envelope listar(@AuthenticationPrincipal Jwt jwt) {
        UUID comprador = exigirPapel(jwt, "comprador");
        return new Envelope(pedidos.listar(comprador).stream()
                .map(pedido -> checkoutPro.complementar((UUID) pedido.get("id"), pedido)).toList(), null);
    }

    @GetMapping("/api/v1/artesao/vendas")
    public Envelope vendasArtesao(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(pedidos.listarVendasArtesao(exigirPapel(jwt, "artesao")), null);
    }

    @GetMapping("/api/v1/artesao/pedidos-pendentes")
    public Envelope pendentesArtesao(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(pedidos.listarPendentesArtesao(exigirPapel(jwt, "artesao")), null);
    }

    @GetMapping("/api/v1/pedidos/{id}")
    public Envelope consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return new Envelope(checkoutPro.complementar(id, pedidos.consultar(id, exigirPapel(jwt, "comprador"))), null);
    }

    @PatchMapping("/api/v1/pedidos/{id}/cancelar")
    public ResponseEntity<Envelope> cancelar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID comprador = exigirPapel(jwt, "comprador");
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador antes da produção.");
        pedidos.processar(id);
        checkoutPro.tentarOperacao(id);
        return resposta(checkoutPro.complementar(id, pedidos.consultar(id, comprador)));
    }

    @PatchMapping("/api/v1/pedidos/{id}/recusar")
    public ResponseEntity<Envelope> recusar(@PathVariable UUID id, @RequestBody Motivo comando,
            @AuthenticationPrincipal Jwt jwt) {
        UUID artesao = exigirPapel(jwt, "artesao");
        pedidos.encerrar(id, artesao, "recusado", comando == null ? null : comando.motivo());
        pedidos.processar(id);
        checkoutPro.tentarOperacao(id);
        return resposta(pedidos.consultarArtesao(id, artesao));
    }

    @PatchMapping("/api/v1/pedidos/{id}/estado")
    public Envelope estado(@PathVariable UUID id, @RequestBody Estado comando,
            @AuthenticationPrincipal Jwt jwt) {
        if (comando == null || !"producao".equals(comando.estado()))
            throw new PedidoException(400, "REQUISICAO_INVALIDA", "Estado inválido.");
        return new Envelope(pedidos.producao(id, exigirPapel(jwt, "artesao")), null);
    }

    @PostMapping("/internal/v1/pedidos/{id}/decisao-mediacao")
    public Envelope mediacao(@PathVariable UUID id, @RequestBody Decisao decisao,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        exigirInterno(token);
        pedidos.mediacao(id, decisao.decisaoId(), decisao.decisao());
        pedidos.processar(id);
        checkoutPro.tentarOperacao(id);
        return new Envelope(pedidos.consultarInterno(id), null);
    }

    @PostMapping("/internal/v1/pedidos/{id}/reprocessar")
    public Envelope reprocessar(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        exigirInterno(token);
        pedidos.processar(id);
        return new Envelope(Map.of("id", id), null);
    }

    private ResponseEntity<Envelope> resposta(Map<String, Object> atual) {
        String estado = (String) atual.get("estado");
        var corpo = new Envelope(atual, null);
        return List.of("encerramento_pendente", "cancelamento_pendente", "reembolso_pendente").contains(estado)
                ? ResponseEntity.accepted().body(corpo)
                : ResponseEntity.ok(corpo);
    }

    private UUID exigirPapel(Jwt jwt, String papel) {
        if (jwt == null || !papel.equals(jwt.getClaimAsString("papel")))
            throw new PedidoException(403, "SEM_PERMISSAO", "Operação não permitida.");
        return UUID.fromString(jwt.getSubject());
    }

    private void exigirInterno(String recebido) {
        if (tokenInterno.length() < 32 || recebido == null || !MessageDigest.isEqual(
                tokenInterno.getBytes(StandardCharsets.UTF_8), recebido.getBytes(StandardCharsets.UTF_8)))
            throw new PedidoException(403, "SEM_PERMISSAO", "Operação interna não permitida.");
    }
}
