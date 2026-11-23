package br.com.balaio.commerce.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.dashboard.domain.PeriodoInvalido;
import br.com.balaio.commerce.dashboard.domain.PeriodoRelatorio;

class PeriodoRelatorioTest {
    private final Clock madrugadaUtc = Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"), ZoneOffset.UTC);

    @Test
    void presetsUsamODiaDeRecifeEIncluemHoje() {
        var trinta = PeriodoRelatorio.resolver(null, null, null, madrugadaUtc);
        assertThat(trinta.ate()).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(trinta.de()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(trinta.dias()).isEqualTo(30);
        assertThat(trinta.preset()).isEqualTo("30d");
        assertThat(PeriodoRelatorio.resolver("7d", null, null, madrugadaUtc).de()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(PeriodoRelatorio.resolver("90d", null, null, madrugadaUtc).dias()).isEqualTo(90);
        var mes = PeriodoRelatorio.resolver("mes", null, null, madrugadaUtc);
        assertThat(mes.de()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(mes.inicio().toString()).isEqualTo("2026-10-01T00:00-03:00");
        assertThat(mes.fimExclusivo().toString()).isEqualTo("2026-11-01T00:00-03:00");
    }

    @Test
    void periodoPersonalizadoValidaOrdemTamanhoEFormato() {
        var personalizado = PeriodoRelatorio.resolver(null, "2026-01-01", "2026-01-31", madrugadaUtc);
        assertThat(personalizado.preset()).isEqualTo("personalizado");
        assertThat(personalizado.dias()).isEqualTo(31);
        assertThat(PeriodoRelatorio.resolver(null, "2025-11-01", "2026-10-31", madrugadaUtc).dias()).isEqualTo(365);
        for (String[] invalido : new String[][] { { "2026-02-01", "2026-01-01" }, { "2025-01-01", "2026-10-31" },
                { "01/01/2026", "2026-01-31" }, { "2026-01-01", null } }) {
            assertThatThrownBy(() -> PeriodoRelatorio.resolver(null, invalido[0], invalido[1], madrugadaUtc))
                    .isInstanceOfSatisfying(PeriodoInvalido.class, erro ->
                            assertThat(erro.codigo()).isEqualTo("PERIODO_INVALIDO"));
        }
        assertThatThrownBy(() -> PeriodoRelatorio.resolver("365d", null, null, madrugadaUtc))
                .isInstanceOf(PeriodoInvalido.class);
        assertThatThrownBy(() -> PeriodoRelatorio.resolver("7d", "2026-01-01", "2026-01-02", madrugadaUtc))
                .isInstanceOf(PeriodoInvalido.class);
    }
}
