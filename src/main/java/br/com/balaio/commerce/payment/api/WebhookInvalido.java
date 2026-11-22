package br.com.balaio.commerce.payment.api;

public class WebhookInvalido extends RuntimeException {
    private final boolean assinaturaInvalida;

    public WebhookInvalido(boolean assinaturaInvalida) {
        super(assinaturaInvalida ? "Notificação inválida." : "Notificação incompatível.");
        this.assinaturaInvalida = assinaturaInvalida;
    }

    public boolean assinaturaInvalida() { return assinaturaInvalida; }
}
