package br.com.balaio.commerce.payment;

import br.com.balaio.commerce.payment.integration.MercadoPagoSandboxConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MercadoPagoSandboxConfigTest {
    private static final String API = "https://api.mercadopago.com";

    @Test
    void ausenciaCompletaDesativaSandboxSemImpedirStartup() {
        var config = new MercadoPagoSandboxConfig("", API, "", "", "", "");
        assertThat(config.enabled()).isFalse();
    }

    @Test
    void configuracaoParcialOuProducaoERecusada() {
        assertThatThrownBy(() -> new MercadoPagoSandboxConfig("sandbox", API, "", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MercadoPagoSandboxConfig("production", API,
                "APP_USR-test", "test-secret", "https://example.com/webhook", "https://example.com"))
                .isInstanceOf(IllegalStateException.class);
    }
}
