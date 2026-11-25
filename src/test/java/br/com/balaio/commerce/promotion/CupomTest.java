package br.com.balaio.commerce.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.RecusaCupom;
import br.com.balaio.commerce.promotion.domain.SituacaoCupom;
import br.com.balaio.commerce.promotion.domain.TipoCupom;

class CupomTest {
    private static final OffsetDateTime AGORA = OffsetDateTime.of(2026, 11, 25, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    @Test
    void percentualArredondaParaCentavosComMeioParaCima() {
        var cupom = cupom(TipoCupom.PERCENTUAL, "15", null);

        var desconto = cupom.calcular(new BigDecimal("33.33"), new BigDecimal("20.00"));

        assertThat(desconto.produtos()).isEqualByComparingTo("5.00");
        assertThat(desconto.produtos().scale()).isEqualTo(2);
        assertThat(desconto.frete()).isEqualByComparingTo("0.00");
        assertThat(cupom(TipoCupom.PERCENTUAL, "10", null).calcular(new BigDecimal("0.05"), BigDecimal.ZERO).produtos())
                .isEqualByComparingTo("0.01");
        assertThat(cupom(TipoCupom.PERCENTUAL, "10", null).calcular(new BigDecimal("0.04"), BigDecimal.ZERO).produtos())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void percentualRespeitaTetoEValorFixoNaoPassaDoSubtotal() {
        assertThat(cupom(TipoCupom.PERCENTUAL, "50", "30.00").calcular(new BigDecimal("200.00"), BigDecimal.ZERO)
                .produtos()).isEqualByComparingTo("30.00");
        assertThat(cupom(TipoCupom.PERCENTUAL, "100", null).calcular(new BigDecimal("80.00"), BigDecimal.ZERO)
                .produtos()).isEqualByComparingTo("80.00");
        assertThat(cupom(TipoCupom.VALOR_FIXO, "25.00", null).calcular(new BigDecimal("100.00"), new BigDecimal("15.00"))
                .total()).isEqualByComparingTo("25.00");
        assertThat(cupom(TipoCupom.VALOR_FIXO, "25.00", null).calcular(new BigDecimal("18.90"), new BigDecimal("15.00"))
                .produtos()).isEqualByComparingTo("18.90");
    }

    @Test
    void freteGratisZeraOFreteAteOTetoSemMexerNasPecas() {
        var semTeto = cupom(TipoCupom.FRETE_GRATIS, "0", null).calcular(new BigDecimal("90.00"), new BigDecimal("42.35"));
        var comTeto = cupom(TipoCupom.FRETE_GRATIS, "0", "30.00").calcular(new BigDecimal("90.00"), new BigDecimal("42.35"));
        var retirada = cupom(TipoCupom.FRETE_GRATIS, "0", null).calcular(new BigDecimal("90.00"), BigDecimal.ZERO);

        assertThat(semTeto.produtos()).isEqualByComparingTo("0");
        assertThat(semTeto.frete()).isEqualByComparingTo("42.35");
        assertThat(comTeto.frete()).isEqualByComparingTo("30.00");
        assertThat(retirada.vazio()).isTrue();
    }

    @Test
    void situacaoConsideraAtivoValidadeEUsos() {
        assertThat(cupom(TipoCupom.VALOR_FIXO, "10", null).situacao(AGORA)).isEqualTo(SituacaoCupom.ATIVO);
        assertThat(comValidade(AGORA.plusMinutes(1), null).situacao(AGORA)).isEqualTo(SituacaoCupom.AGENDADO);
        assertThat(comValidade(null, AGORA.minusSeconds(1)).situacao(AGORA)).isEqualTo(SituacaoCupom.EXPIRADO);
        assertThat(comValidade(AGORA, AGORA).situacao(AGORA)).isEqualTo(SituacaoCupom.ATIVO);
        assertThat(comUsos(5, 5, true).situacao(AGORA)).isEqualTo(SituacaoCupom.ESGOTADO);
        assertThat(comUsos(5, 4, true).situacao(AGORA)).isEqualTo(SituacaoCupom.ATIVO);
        assertThat(comUsos(5, 5, false).situacao(AGORA)).isEqualTo(SituacaoCupom.INATIVO);
    }

    @Test
    void recusaExplicaMotivoComMensagemClara() {
        assertThat(comValidade(null, AGORA.minusDays(1)).recusa(AGORA, new BigDecimal("100"), 0))
                .hasValueSatisfying(recusa -> {
                    assertThat(recusa.motivo()).isEqualTo(RecusaCupom.EXPIRADO);
                    assertThat(recusa.mensagem()).isEqualTo("Este cupom expirou.");
                });
        assertThat(comUsos(1, 1, true).recusa(AGORA, new BigDecimal("100"), 0))
                .hasValueSatisfying(recusa -> assertThat(recusa.motivo()).isEqualTo(RecusaCupom.ESGOTADO));
        var minimo = new Cupom(UUID.randomUUID(), "MINIMO", "Pedido mínimo", TipoCupom.VALOR_FIXO, new BigDecimal("10"),
                null, new BigDecimal("1500.00"), null, null, null, 1, 0, true, AGORA, AGORA);
        assertThat(minimo.recusa(AGORA, new BigDecimal("1499.99"), 0)).hasValueSatisfying(recusa -> {
            assertThat(recusa.motivo()).isEqualTo(RecusaCupom.PEDIDO_MINIMO);
            assertThat(recusa.mensagem()).isEqualTo("Este cupom vale para pedidos a partir de R$ 1.500,00 em peças.");
        });
        assertThat(minimo.recusa(AGORA, new BigDecimal("1500.00"), 0)).isEmpty();
        assertThat(minimo.recusa(AGORA, new BigDecimal("1500.00"), 1))
                .hasValueSatisfying(recusa -> assertThat(recusa.motivo()).isEqualTo(RecusaCupom.LIMITE_POR_COMPRADOR));
    }

    private static Cupom cupom(TipoCupom tipo, String valor, String teto) {
        return new Cupom(UUID.randomUUID(), "TESTE10", "Teste", tipo, new BigDecimal(valor),
                teto == null ? null : new BigDecimal(teto), BigDecimal.ZERO, null, null, null, null, 0, true, AGORA, AGORA);
    }

    private static Cupom comValidade(OffsetDateTime inicio, OffsetDateTime fim) {
        return new Cupom(UUID.randomUUID(), "VALIDADE", "Validade", TipoCupom.VALOR_FIXO, BigDecimal.TEN, null,
                BigDecimal.ZERO, inicio, fim, null, null, 0, true, AGORA, AGORA);
    }

    private static Cupom comUsos(int limite, int usos, boolean ativo) {
        return new Cupom(UUID.randomUUID(), "USOS", "Usos", TipoCupom.VALOR_FIXO, BigDecimal.TEN, null,
                BigDecimal.ZERO, null, null, limite, null, usos, ativo, AGORA, AGORA);
    }
}
