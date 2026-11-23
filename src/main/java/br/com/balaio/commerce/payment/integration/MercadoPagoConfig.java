package br.com.balaio.commerce.payment.integration;

import br.com.balaio.commerce.payment.application.port.out.AmbientePagamento;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MercadoPagoConfig implements AmbientePagamento {
    public static final String AMBIENTE_SANDBOX = "sandbox";
    public static final String AMBIENTE_PRODUCAO = "production";
    private static final Set<String> HOSTS_CHECKOUT_PRODUCAO = Set.of("www.mercadopago.com", "www.mercadopago.com.br");
    private static final Set<String> HOSTS_CHECKOUT_SANDBOX = Set.of("www.mercadopago.com", "www.mercadopago.com.br",
            "sandbox.mercadopago.com", "sandbox.mercadopago.com.br");

    private final String accessToken;
    private final String webhookSecret;
    private final String notificationUrl;
    private final String frontendReturnUrl;
    private final String baseUrl;
    private final boolean enabled;
    private final boolean producao;

    public MercadoPagoConfig(
            @Value("${payment.mercadopago.environment:}") String environment,
            @Value("${payment.mercadopago.base-url:}") String baseUrl,
            @Value("${payment.mercadopago.access-token:}") String accessToken,
            @Value("${payment.mercadopago.webhook-secret:}") String webhookSecret,
            @Value("${payment.mercadopago.notification-url:}") String notificationUrl,
            @Value("${payment.mercadopago.frontend-return-url:}") String frontendReturnUrl) {
        boolean absent = (environment == null || environment.isBlank())
                && (accessToken == null || accessToken.isBlank())
                && (webhookSecret == null || webhookSecret.isBlank())
                && (notificationUrl == null || notificationUrl.isBlank())
                && (frontendReturnUrl == null || frontendReturnUrl.isBlank());
        boolean ambienteConhecido = AMBIENTE_SANDBOX.equals(environment) || AMBIENTE_PRODUCAO.equals(environment);
        if (!absent && (!ambienteConhecido || !"https://api.mercadopago.com".equals(baseUrl)
                || accessToken == null || !accessToken.startsWith("APP_USR-")
                || webhookSecret == null || webhookSecret.isBlank()
                || !publicHttps(notificationUrl) || !publicHttps(frontendReturnUrl)))
            throw new IllegalStateException("Configuração do Mercado Pago incompleta ou insegura.");
        this.enabled = !absent;
        this.producao = !absent && AMBIENTE_PRODUCAO.equals(environment);
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.webhookSecret = webhookSecret;
        this.notificationUrl = notificationUrl;
        this.frontendReturnUrl = frontendReturnUrl == null ? "" : frontendReturnUrl.replaceAll("/+$", "");
    }

    private boolean publicHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && !uri.getHost().equals("localhost");
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public String campoUrlCheckout() {
        return "init_point";
    }

    public boolean hostCheckoutPermitido(String host) {
        return (producao ? HOSTS_CHECKOUT_PRODUCAO : HOSTS_CHECKOUT_SANDBOX).contains(host);
    }

    public String baseUrl() { return baseUrl; }
    public String accessToken() { return accessToken; }
    public String webhookSecret() { return webhookSecret; }
    public String notificationUrl() { return notificationUrl; }
    public String returnUrl() { return frontendReturnUrl; }
    @Override
    public boolean enabled() { return enabled; }
    @Override
    public boolean producao() { return producao; }
}
