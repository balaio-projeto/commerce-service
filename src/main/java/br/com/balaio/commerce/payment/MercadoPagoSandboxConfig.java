package br.com.balaio.commerce.payment;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MercadoPagoSandboxConfig {
    private final String accessToken;
    private final String webhookSecret;
    private final String notificationUrl;
    private final String frontendReturnUrl;
    private final String baseUrl;
    private final boolean enabled;

    public MercadoPagoSandboxConfig(
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
        if (!absent && (!"sandbox".equals(environment) || !"https://api.mercadopago.com".equals(baseUrl)
                || accessToken == null || !accessToken.startsWith("APP_USR-")
                || webhookSecret == null || webhookSecret.isBlank()
                || !publicHttps(notificationUrl) || !publicHttps(frontendReturnUrl)))
            throw new IllegalStateException("Configuração sandbox do Mercado Pago incompleta ou insegura.");
        this.enabled = !absent;
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

    public String baseUrl() { return baseUrl; }
    public String accessToken() { return accessToken; }
    public String webhookSecret() { return webhookSecret; }
    public String notificationUrl() { return notificationUrl; }
    public String returnUrl() { return frontendReturnUrl; }
    public boolean enabled() { return enabled; }
}
