package br.com.balaio.commerce.dashboard.application.port.out;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface VendasArtesaoRepository {
    List<LinhaVenda> vendas(UUID artesao, OffsetDateTime inicio, OffsetDateTime fimExclusivo);
    Map<String, Integer> pedidosAtivosPorEstado(UUID artesao);

    record LinhaVenda(UUID pedido, BigDecimal frete, String slug, int quantidade, BigDecimal preco,
            OffsetDateTime vendidoEm) { }
}
