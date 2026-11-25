package br.com.balaio.commerce.promotion.application.port.in;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.promotion.domain.DescontoCalculado;
import br.com.balaio.commerce.promotion.domain.TipoCupom;

public interface AplicarCupomUseCase {
    Optional<Aplicacao> avaliar(String codigo, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete);
    Aplicacao reservar(Aplicacao prevista, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete);
    void registrarUso(Aplicacao aplicacao, UUID pedido, UUID comprador, BigDecimal descontoAplicado);

    record Aplicacao(UUID cupomId, String codigo, String descricao, TipoCupom tipo, boolean automatico,
            DescontoCalculado desconto, Map<String, Object> snapshot) { }
}
