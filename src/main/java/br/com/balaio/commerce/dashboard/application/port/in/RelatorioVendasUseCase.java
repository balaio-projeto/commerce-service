package br.com.balaio.commerce.dashboard.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RelatorioVendasUseCase {
    Relatorio relatorio(UUID artesao, String periodo, String de, String ate);

    record Relatorio(Periodo periodo, BigDecimal receita, BigDecimal frete, int quantidadePedidos,
            int unidadesVendidas, BigDecimal ticketMedio, List<String> estadosConsiderados, String formulaReceita,
            List<ItemRanking> ranking, Serie serie) { }
    record Periodo(String de, String ate, String preset, String fusoHorario) { }
    record ItemRanking(String slug, int unidades, int pedidos, BigDecimal receita, BigDecimal participacao) { }
    record Serie(String granularidade, List<Ponto> pontos) { }
    record Ponto(String inicio, BigDecimal receita) { }
}
