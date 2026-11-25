package br.com.balaio.commerce.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.dashboard.application.port.in.ResumoPainelUseCase.Resumo;
import br.com.balaio.commerce.dashboard.application.port.out.PecasDoPainel;
import br.com.balaio.commerce.dashboard.application.port.out.PecasDoPainel.PecaPainel;
import br.com.balaio.commerce.dashboard.application.port.out.VendasArtesaoRepository;

class ResumoPainelServiceTest {

    private static final UUID ARTESAO = UUID.randomUUID();

    /** Repositório de vendas em memória; {@code atrasoMs} simula o custo de uma consulta local. */
    private static VendasArtesaoRepository vendas(long atrasoMs) {
        return new VendasArtesaoRepository() {
            @Override
            public List<LinhaVenda> vendas(UUID artesao, OffsetDateTime inicio, OffsetDateTime fimExclusivo) {
                return List.of();
            }

            @Override
            public Map<String, Integer> pedidosAtivosPorEstado(UUID artesao) {
                pausa(atrasoMs);
                return Map.of("confirmado", 2, "producao", 1, "enviado", 0);
            }
        };
    }

    /** Catálogo em memória; {@code atrasoMs} simula a latência do catalog-service. */
    private static PecasDoPainel catalogo(long atrasoMs, List<PecaPainel> pecas) {
        return (artesao, token) -> {
            pausa(atrasoMs);
            return pecas;
        };
    }

    private static PecasDoPainel catalogoFora() {
        return (artesao, token) -> {
            throw new IllegalStateException("catálogo fora do ar");
        };
    }

    private static ResumoPainelService servico(VendasArtesaoRepository vendas, PecasDoPainel catalogo, boolean paralelo) {
        return new ResumoPainelService(vendas, new RelatorioVendasService(vendas), catalogo, paralelo);
    }

    private static final List<PecaPainel> PECAS = List.of(
            new PecaPainel("a", "Peça A", "disponivel"),
            new PecaPainel("b", "Peça B", "vendida"),
            new PecaPainel("c", "Peça C", "reservada"));

    @Test
    void osDoisModosDevolvemOMesmoResultado() {
        var paralelo = servico(vendas(0), catalogo(0, PECAS), true).resumo(ARTESAO, "token");
        var sequencial = servico(vendas(0), catalogo(0, PECAS), false).resumo(ARTESAO, "token");

        assertThat(sequencial.pecasAtivas()).isEqualTo(paralelo.pecasAtivas()).isEqualTo(2L);
        assertThat(sequencial.avisos()).hasSize(1).isEqualTo(paralelo.avisos());
        assertThat(sequencial.pedidosAAceitar()).isEqualTo(paralelo.pedidosAAceitar()).isEqualTo(2);
        assertThat(sequencial.faturamentoMes()).isEqualByComparingTo(paralelo.faturamentoMes());
        assertThat(sequencial.parcial()).isFalse();
        assertThat(paralelo.parcial()).isFalse();
    }

    @Test
    void falhaDoCatalogoEhTratadaIgualNosDoisModos() {
        for (boolean paralelo : List.of(true, false)) {
            Resumo resumo = servico(vendas(0), catalogoFora(), paralelo).resumo(ARTESAO, "token");

            assertThat(resumo.parcial()).isTrue();
            assertThat(resumo.fontesIndisponiveis()).containsExactly("catalogo");
            assertThat(resumo.pecasAtivas()).isNull();
            // as consultas locais continuam sendo respondidas mesmo com o catálogo fora
            assertThat(resumo.pedidosAAceitar()).isEqualTo(2);
            assertThat(resumo.emProducao()).isEqualTo(1);
        }
    }

    @Test
    void modoSequencialSomaLatenciasEModoParaleloAsSobrepoe() {
        // catálogo e consultas locais com 300 ms cada: sequencial ≈ 600 ms, paralelo ≈ 300 ms
        var sequencial = servico(vendas(300), catalogo(300, PECAS), false).resumo(ARTESAO, "token");
        var paralelo = servico(vendas(300), catalogo(300, PECAS), true).resumo(ARTESAO, "token");

        assertThat(sequencial.duracaoMs()).isGreaterThanOrEqualTo(550);
        assertThat(paralelo.duracaoMs()).isLessThan(500);
    }

    private static void pausa(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
