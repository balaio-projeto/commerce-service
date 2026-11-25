package br.com.balaio.commerce.order.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.order.application.port.in.ConsultarVendasArtesaoUseCase;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;

@RestController
public class VendasArtesaoController {
    private final ConsultarVendasArtesaoUseCase vendas;

    public VendasArtesaoController(ConsultarVendasArtesaoUseCase vendas) {
        this.vendas = vendas;
    }

    @GetMapping("/api/v1/artesao/vendas")
    public Envelope vendas(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(vendas.vendas(PapelJwt.exigir(jwt, "artesao")).stream()
                .map(VendaArtesaoResponse::de).toList(), null);
    }

    @GetMapping("/api/v1/artesao/pedidos-pendentes")
    public Envelope pendentes(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(vendas.pendentes(PapelJwt.exigir(jwt, "artesao")).stream()
                .map(VendaArtesaoResponse.PendenteResponse::de).toList(), null);
    }

    @GetMapping("/api/v1/artesao/pedidos/{id}")
    public Envelope pedido(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return new Envelope(VendaArtesaoResponse.de(vendas.venda(id, PapelJwt.exigir(jwt, "artesao"))), null);
    }
}
