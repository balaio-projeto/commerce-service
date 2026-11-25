package br.com.balaio.commerce.payment.simulador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SimuladorPagamentoTest {

    @Test
    void simuladorSemCredenciaisMercadoPagoEstaHabilitadoEmAmbienteDeTeste() {
        var config = new SimuladorPagamentoConfig("", "", "", "", "");

        assertThat(config.enabled()).isTrue();
        assertThat(config.producao()).isFalse();
    }

    @Test
    void simuladorNaoIniciaComCredenciaisDoMercadoPago() {
        assertThatThrownBy(() -> new SimuladorPagamentoConfig("sandbox", "", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mercado Pago");
        assertThatThrownBy(() -> new SimuladorPagamentoConfig("", "APP_USR-123", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aprovacaoGeraPagamentoQueReferenciaAPreferenciaEOPedido() {
        var gateway = new SimuladorPagamentoGateway();
        var pedido = UUID.randomUUID();
        var preferencia = gateway.criar(pedido, java.util.List.of(), BigDecimal.TEN);

        String pagamentoId = gateway.aprovar(pedido, new BigDecimal("138.90"));
        var pagamento = gateway.pagamento(pagamentoId);
        var ordem = gateway.ordem(pagamento.ordemId());

        assertThat(pagamento.status()).isEqualTo("approved");
        assertThat(pagamento.liveMode()).isFalse();
        assertThat(pagamento.moeda()).isEqualTo("BRL");
        assertThat(pagamento.valor()).isEqualByComparingTo("138.90");
        assertThat(pagamento.referencia()).isEqualTo(pedido.toString());
        assertThat(ordem.preferenciaId()).isEqualTo(preferencia.id());
        assertThat(ordem.referencia()).isEqualTo(pedido.toString());
        assertThat(gateway.buscarPagamentos(pedido.toString())).containsExactly(pagamentoId);
    }

    @Test
    void aprovarSemPreferenciaFalha() {
        var gateway = new SimuladorPagamentoGateway();

        assertThatThrownBy(() -> gateway.aprovar(UUID.randomUUID(), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
    }
}
