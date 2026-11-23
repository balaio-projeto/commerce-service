package br.com.balaio.commerce.order.application.port.out;

import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;

public interface FingerprintCheckout {
    String calcular(Checkout comando);
}
