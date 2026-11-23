package br.com.balaio.commerce.dashboard.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.commerce.dashboard.application.port.in.RelatorioVendasUseCase;
import br.com.balaio.commerce.dashboard.application.port.out.VendasArtesaoRepository;
import br.com.balaio.commerce.dashboard.application.port.out.VendasArtesaoRepository.LinhaVenda;
import br.com.balaio.commerce.dashboard.domain.PeriodoRelatorio;

@Service
public class RelatorioVendasService implements RelatorioVendasUseCase {
    public static final List<String> ESTADOS_DE_VENDA = List.of("confirmado", "producao", "enviado", "entregue");
    public static final String FORMULA_RECEITA = "Soma de preço cotado × quantidade dos itens de pedidos do artesão "
            + "nos estados confirmado, producao, enviado ou entregue e sem encerramento em andamento. "
            + "O frete fica fora da receita e é informado à parte. A data da venda é a da confirmação do pagamento "
            + "(etapa confirmado) no fuso America/Recife; pedidos sem essa etapa usam a data de criação. "
            + "Pedidos aguardando pagamento, cancelados, recusados, reembolsados ou com estorno pendente não entram.";
    private final VendasArtesaoRepository vendas;
    private final Clock relogio;

    @Autowired
    public RelatorioVendasService(VendasArtesaoRepository vendas) {
        this(vendas, Clock.systemUTC());
    }

    public RelatorioVendasService(VendasArtesaoRepository vendas, Clock relogio) {
        this.vendas = vendas;
        this.relogio = relogio;
    }

    @Override
    @Transactional(readOnly = true)
    public Relatorio relatorio(UUID artesao, String preset, String de, String ate) {
        var periodo = PeriodoRelatorio.resolver(preset, de, ate, relogio);
        var linhas = vendas(artesao, periodo);
        var pedidos = new HashSet<UUID>();
        var fretePorPedido = new LinkedHashMap<UUID, BigDecimal>();
        var ranking = new LinkedHashMap<String, Acumulado>();
        var serie = serieVazia(periodo);
        BigDecimal receita = BigDecimal.ZERO;
        int unidades = 0;
        for (var linha : linhas) {
            BigDecimal valor = linha.preco().multiply(BigDecimal.valueOf(linha.quantidade()));
            receita = receita.add(valor);
            unidades += linha.quantidade();
            pedidos.add(linha.pedido());
            fretePorPedido.putIfAbsent(linha.pedido(), linha.frete());
            ranking.computeIfAbsent(linha.slug(), Acumulado::new).somar(linha, valor);
            serie.computeIfPresent(chaveDaSerie(periodo, linha.vendidoEm()), (chave, atual) -> atual.add(valor));
        }
        BigDecimal frete = fretePorPedido.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ticket = pedidos.isEmpty() ? BigDecimal.ZERO
                : receita.divide(BigDecimal.valueOf(pedidos.size()), 2, RoundingMode.HALF_UP);
        BigDecimal receitaTotal = receita;
        var itensRanking = ranking.values().stream()
                .sorted(Comparator.comparing(Acumulado::receita).reversed()
                        .thenComparing(Comparator.comparingInt(Acumulado::unidades).reversed())
                        .thenComparing(Acumulado::slug))
                .map(item -> item.resposta(receitaTotal)).toList();
        var pontos = serie.entrySet().stream()
                .map(ponto -> new Ponto(ponto.getKey(), ponto.getValue().setScale(2, RoundingMode.HALF_UP))).toList();
        return new Relatorio(new Periodo(periodo.de().toString(), periodo.ate().toString(), periodo.preset(),
                PeriodoRelatorio.FUSO.getId()), receita.setScale(2, RoundingMode.HALF_UP),
                frete.setScale(2, RoundingMode.HALF_UP), pedidos.size(), unidades, ticket, ESTADOS_DE_VENDA,
                FORMULA_RECEITA, itensRanking, new Serie(granularidade(periodo), pontos));
    }

    @Transactional(readOnly = true)
    public BigDecimal receitaDoMes(UUID artesao) {
        var periodo = PeriodoRelatorio.resolver("mes", null, null, relogio);
        return vendas(artesao, periodo).stream()
                .map(linha -> linha.preco().multiply(BigDecimal.valueOf(linha.quantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private List<LinhaVenda> vendas(UUID artesao, PeriodoRelatorio periodo) {
        return vendas.vendas(artesao, periodo.inicio(), periodo.fimExclusivo());
    }

    private String granularidade(PeriodoRelatorio periodo) {
        return periodo.dias() <= 31 ? "dia" : "mes";
    }

    private TreeMap<String, BigDecimal> serieVazia(PeriodoRelatorio periodo) {
        var serie = new TreeMap<String, BigDecimal>();
        if (periodo.dias() <= 31) {
            for (LocalDate dia = periodo.de(); !dia.isAfter(periodo.ate()); dia = dia.plusDays(1))
                serie.put(dia.toString(), BigDecimal.ZERO);
        } else {
            for (YearMonth mes = YearMonth.from(periodo.de()); !mes.isAfter(YearMonth.from(periodo.ate()));
                    mes = mes.plusMonths(1))
                serie.put(mes.atDay(1).toString(), BigDecimal.ZERO);
        }
        return serie;
    }

    private String chaveDaSerie(PeriodoRelatorio periodo, OffsetDateTime vendidoEm) {
        LocalDate dia = vendidoEm.atZoneSameInstant(PeriodoRelatorio.FUSO).toLocalDate();
        return periodo.dias() <= 31 ? dia.toString() : dia.withDayOfMonth(1).toString();
    }

    private static final class Acumulado {
        private final String slug;
        private final Set<UUID> pedidos = new HashSet<>();
        private BigDecimal receita = BigDecimal.ZERO;
        private int unidades;

        private Acumulado(String slug) {
            this.slug = slug;
        }

        private void somar(LinhaVenda linha, BigDecimal valor) {
            receita = receita.add(valor);
            unidades += linha.quantidade();
            pedidos.add(linha.pedido());
        }

        private String slug() { return slug; }
        private BigDecimal receita() { return receita; }
        private int unidades() { return unidades; }

        private ItemRanking resposta(BigDecimal total) {
            return new ItemRanking(slug, unidades, pedidos.size(), receita.setScale(2, RoundingMode.HALF_UP),
                    total.signum() == 0 ? BigDecimal.ZERO
                            : receita.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP));
        }
    }
}
