package br.com.balaio.commerce.payment;

import br.com.balaio.commerce.payment.integration.MercadoPagoConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MercadoPagoConfigTest {
    private static final String API = "https://api.mercadopago.com";
    private static final String WEBHOOK = "https://example.com/webhook";
    private static final String RETORNO = "https://example.com";

    @Test
    void ausenciaCompletaDesativaPagamentoSemImpedirStartup() {
        var config = new MercadoPagoConfig("", API, "", "", "", "");
        assertThat(config.enabled()).isFalse();
        assertThat(config.producao()).isFalse();
    }

    @Test
    void configuracaoParcialOuAmbienteDesconhecidoERecusado() {
        assertThatThrownBy(() -> new MercadoPagoConfig("sandbox", API, "", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MercadoPagoConfig("live", API, "APP_USR-token", "segredo", WEBHOOK, RETORNO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MercadoPagoConfig("production", API, "APP_USR-token", "segredo",
                "http://example.com/webhook", RETORNO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sandboxUsaOCheckoutAtualComCredenciaisDeTeste() {
        var config = new MercadoPagoConfig("sandbox", API, "APP_USR-token", "segredo", WEBHOOK, RETORNO);
        assertThat(config.enabled()).isTrue();
        assertThat(config.producao()).isFalse();
        assertThat(config.campoUrlCheckout()).isEqualTo("init_point");
        assertThat(config.hostCheckoutPermitido("www.mercadopago.com.br")).isTrue();
        assertThat(config.hostCheckoutPermitido("sandbox.mercadopago.com.br")).isTrue();
        assertThat(config.hostCheckoutPermitido("mercadopago.com.br.example.com")).isFalse();
    }

    @Test
    void producaoUsaLinkRealDoCheckout() {
        var config = new MercadoPagoConfig("production", API, "APP_USR-token", "segredo", WEBHOOK, RETORNO);
        assertThat(config.enabled()).isTrue();
        assertThat(config.producao()).isTrue();
        assertThat(config.campoUrlCheckout()).isEqualTo("init_point");
        assertThat(config.hostCheckoutPermitido("www.mercadopago.com.br")).isTrue();
        assertThat(config.hostCheckoutPermitido("sandbox.mercadopago.com.br")).isFalse();
    }
}
