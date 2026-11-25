package br.com.balaio.commerce.payment.simulador;

import br.com.balaio.commerce.payment.application.port.out.AmbientePagamento;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Ambiente de pagamento de desenvolvimento. Ativo só com balaio.pagamento.simulador.ativo=true.
 * Não coexiste com credenciais do Mercado Pago: se qualquer uma estiver configurada, o serviço não inicia.
 */
@Component
@Primary
@ConditionalOnProperty(name = "balaio.pagamento.simulador.ativo", havingValue = "true")
public class SimuladorPagamentoConfig implements AmbientePagamento {

    public SimuladorPagamentoConfig(
            @Value("${payment.mercadopago.environment:}") String ambiente,
            @Value("${payment.mercadopago.access-token:}") String accessToken,
            @Value("${payment.mercadopago.webhook-secret:}") String webhookSecret,
            @Value("${payment.mercadopago.notification-url:}") String notificationUrl,
            @Value("${payment.mercadopago.frontend-return-url:}") String frontendReturnUrl) {
        if (!ambiente.isBlank() || !accessToken.isBlank() || !webhookSecret.isBlank()
                || !notificationUrl.isBlank() || !frontendReturnUrl.isBlank())
            throw new IllegalStateException(
                    "Simulador de pagamento não pode coexistir com configuração do Mercado Pago.");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean producao() {
        return false;
    }
}
