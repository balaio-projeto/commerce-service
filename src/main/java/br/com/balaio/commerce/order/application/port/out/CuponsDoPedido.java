package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CuponsDoPedido {
    Optional<CupomDoPedido> avaliar(String codigo, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete);
    CupomDoPedido reservar(CupomDoPedido previsto, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete);
    void registrarUso(CupomDoPedido cupom, UUID pedido, UUID comprador, BigDecimal descontoAplicado);

    record CupomDoPedido(UUID id, String codigo, String descricao, String tipo, boolean automatico,
            BigDecimal descontoProdutos, BigDecimal descontoFrete, Map<String, Object> snapshot) { }
}
