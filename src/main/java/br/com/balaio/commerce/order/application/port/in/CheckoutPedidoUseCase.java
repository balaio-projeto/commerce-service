package br.com.balaio.commerce.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutPedidoUseCase {
    UUID checkout(Checkout comando, UUID comprador, String chave);
    UUID checkoutSalvo(Checkout comando, UUID comprador, String chave, String tokenComprador);
    Optional<PreviaCupom> simularCupom(List<Item> itens, String cupom, UUID comprador, BigDecimal frete);

    record Item(String slug, Integer quantidade) { }

    record Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco,
            UUID enderecoId, String cupom) {
        public Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco) {
            this(itens, freteId, meio, endereco, null, null);
        }

        public Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco, UUID enderecoId) {
            this(itens, freteId, meio, endereco, enderecoId, null);
        }
    }

    record PreviaCupom(String codigo, String descricao, String tipo, boolean automatico, BigDecimal subtotalProdutos,
            BigDecimal descontoProdutos, BigDecimal descontoFrete, String custeadoPor) { }
}
