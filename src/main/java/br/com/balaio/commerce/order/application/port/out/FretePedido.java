package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FretePedido {
    boolean reconhece(String freteId);
    FreteEscolhido escolher(String freteId, String cep, List<ItemFrete> itens);

    record ItemFrete(String slug, int quantidade, BigDecimal valorUnitario) { }
    record FreteEscolhido(String id, BigDecimal valor, Map<String, Object> snapshot) { }
}
