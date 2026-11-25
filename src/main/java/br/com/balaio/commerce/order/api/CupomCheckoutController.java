package br.com.balaio.commerce.order.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;

@RestController
public class CupomCheckoutController {
    private final CheckoutPedidoUseCase checkout;

    public CupomCheckoutController(CheckoutPedidoUseCase checkout) {
        this.checkout = checkout;
    }

    public record Item(String slug, Integer quantidade) { }
    public record Simulacao(List<Item> itens, String cupom, BigDecimal frete) { }

    @PostMapping("/api/v1/checkout/cupom")
    public Envelope simular(@RequestBody Simulacao corpo, @AuthenticationPrincipal Jwt jwt) {
        var comprador = PapelJwt.exigirQuemCompra(jwt);
        if (corpo == null || corpo.itens() == null)
            throw new PedidoException(PedidoException.Tipo.REQUISICAO_INVALIDA, "Informe os itens do checkout.");
        var itens = corpo.itens().stream()
                .map(item -> item == null ? null : new CheckoutPedidoUseCase.Item(item.slug(), item.quantidade())).toList();
        return new Envelope(checkout.simularCupom(itens, corpo.cupom(), comprador, corpo.frete()).orElse(null), null);
    }
}
