package br.com.balaio.commerce.order.api;

import java.util.UUID;

import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.TokenInterno;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidosInternosController {
    private static final String TAG_PEDIDOS = "pedidos-controller";

    private final CheckoutUseCase checkout;
    private final ConsultarPedidosUseCase pedidos;
    private final ProcessarPedidosPendentesUseCase processamento;
    private final TokenInterno tokenInterno;

    public PedidosInternosController(CheckoutUseCase checkout, ConsultarPedidosUseCase pedidos,
            ProcessarPedidosPendentesUseCase processamento, TokenInterno tokenInterno) {
        this.checkout = checkout;
        this.pedidos = pedidos;
        this.processamento = processamento;
        this.tokenInterno = tokenInterno;
    }

    public record Decisao(UUID decisaoId, String decisao) { }
    public record Reprocessado(UUID id) { }

    @GetMapping("/internal/v1/pedidos/{id}")
    @Operation(tags = TAG_PEDIDOS)
    public Envelope consultarInterno(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        return new Envelope(PedidoResponse.de(pedidos.consultarInterno(id)), null);
    }

    @GetMapping("/internal/v1/pedidos/{id}/participantes")
    @Operation(tags = TAG_PEDIDOS)
    public Envelope participantes(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        return new Envelope(pedidos.participantes(id), null);
    }

    @PostMapping("/internal/v1/pedidos/{id}/decisao-mediacao")
    @Operation(tags = TAG_PEDIDOS)
    public Envelope mediacao(@PathVariable UUID id, @RequestBody Decisao decisao,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        return new Envelope(PedidoResponse.de(checkout.mediacao(id, decisao.decisaoId(), decisao.decisao())), null);
    }

    @PostMapping("/internal/v1/pedidos/{id}/reprocessar")
    @Operation(tags = TAG_PEDIDOS)
    public Envelope reprocessar(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        processamento.processar(id);
        return new Envelope(new Reprocessado(id), null);
    }
}
