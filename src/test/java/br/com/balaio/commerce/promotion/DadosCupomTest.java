package br.com.balaio.commerce.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.promotion.domain.DadosCupom;

class DadosCupomTest {
    private static final OffsetDateTime INICIO = OffsetDateTime.parse("2026-12-01T00:00:00-03:00");

    @Test
    void normalizaCodigoEMantemPromocaoAutomaticaSemCodigo() {
        var dados = new DadosCupom("  natal10 ", " Natal ", "percentual", new BigDecimal("10"), null, null,
                null, null, null, null, null).normalizado();
        var automatica = new DadosCupom("   ", "Frete grátis de dezembro", "frete_gratis", new BigDecimal("99"),
                null, null, null, null, null, null, false).normalizado();

        assertThat(dados.codigo()).isEqualTo("NATAL10");
        assertThat(dados.descricao()).isEqualTo("Natal");
        assertThat(dados.pedidoMinimo()).isEqualByComparingTo("0");
        assertThat(dados.ativo()).isTrue();
        assertThat(dados.problemas(0)).isEmpty();
        assertThat(automatica.codigo()).isNull();
        assertThat(automatica.valor()).isEqualByComparingTo("0");
        assertThat(automatica.ativo()).isFalse();
        assertThat(automatica.problemas(0)).isEmpty();
    }

    @Test
    void apontaCadaCampoInvalido() {
        var dados = new DadosCupom("É-INVÁLIDO", "x", "brinde", null, new BigDecimal("-1"), new BigDecimal("10.001"),
                INICIO, INICIO.minusDays(1), 0, 0, true).normalizado();

        assertThat(dados.problemas(0)).containsOnlyKeys("codigo", "descricao", "tipo", "descontoMaximo", "pedidoMinimo",
                "fim", "limiteUsos", "limitePorComprador");
    }

    @Test
    void validaValorConformeOTipoELimiteAbaixoDosUsos() {
        assertThat(dados("percentual", "0").problemas(0)).containsKey("valor");
        assertThat(dados("percentual", "100.5").problemas(0)).containsKey("valor");
        assertThat(dados("percentual", "12.5").problemas(0)).isEmpty();
        assertThat(dados("valor_fixo", "0").problemas(0)).containsKey("valor");
        assertThat(dados("valor_fixo", "19.999").problemas(0)).containsKey("valor");
        assertThat(dados("frete_gratis", null).problemas(0)).isEmpty();
        var comLimite = new DadosCupom("LIMITE", "Limite", "valor_fixo", BigDecimal.TEN, null, null, null, null, 3, null, true).normalizado();
        assertThat(comLimite.problemas(4)).containsEntry("limiteUsos", "O limite não pode ser menor que os 4 usos já registrados.");
        assertThat(comLimite.problemas(3)).isEmpty();
    }

    private static DadosCupom dados(String tipo, String valor) {
        return new DadosCupom("CODIGO", "Descrição", tipo, valor == null ? null : new BigDecimal(valor), null, null,
                null, null, null, null, true).normalizado();
    }
}
