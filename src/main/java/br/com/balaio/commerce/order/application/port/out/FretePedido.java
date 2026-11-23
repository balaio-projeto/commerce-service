package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface FretePedido {
    boolean reconhece(String freteId);
    FreteEscolhido escolher(String freteId, String cep, UUID artesao, List<ItemFrete> itens);

    record ItemFrete(String slug, int quantidade, BigDecimal valorUnitario) { }
    record FreteEscolhido(String id, BigDecimal valor, Map<String, Object> snapshot) { }
}
