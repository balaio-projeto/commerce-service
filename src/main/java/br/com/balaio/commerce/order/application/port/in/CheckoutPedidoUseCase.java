package br.com.balaio.commerce.order.application.port.in;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CheckoutPedidoUseCase {
    UUID checkout(Checkout comando, UUID comprador, String chave);
    UUID checkoutSalvo(Checkout comando, UUID comprador, String chave, String tokenComprador);

    record Item(String slug, Integer quantidade) { }

    record Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco,
            UUID enderecoId) {
        public Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco) {
            this(itens, freteId, meio, endereco, null);
        }
    }
}
