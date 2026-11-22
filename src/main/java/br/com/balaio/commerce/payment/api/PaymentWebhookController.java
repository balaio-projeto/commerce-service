package br.com.balaio.commerce.payment.api;

import java.time.Duration;

import br.com.balaio.commerce.payment.application.port.in.RegistrarNotificacaoUseCase;
import br.com.balaio.commerce.payment.integration.MercadoPagoSandboxConfig;
import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
public class PaymentWebhookController {
    private final RegistrarNotificacaoUseCase pagamentos;
    private final MercadoPagoSandboxConfig config;

    public PaymentWebhookController(RegistrarNotificacaoUseCase pagamentos, MercadoPagoSandboxConfig config) {
        this.pagamentos = pagamentos;
        this.config = config;
    }

    @PostMapping("/api/v1/pagamentos/mercado-pago/notificacoes")
    public ResponseEntity<Void> receber(@RequestHeader(value = "x-signature", required = false) String assinatura,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(name = "data.id", required = false) String dataId,
            @RequestBody JsonNode aviso) {
        pagamentos.exigirDisponivel();
        if (assinatura == null || assinatura.isBlank() || requestId == null || requestId.isBlank()
                || requestId.length() > 160 || dataId == null
                || !dataId.matches("[0-9]{1,80}")) throw new WebhookInvalido(true);
        try {
            WebhookSignatureValidator.validate(assinatura, requestId, dataId, config.webhookSecret(),
                    Duration.ofMinutes(10));
        } catch (MPInvalidWebhookSignatureException | IllegalArgumentException invalid) {
            throw new WebhookInvalido(true);
        }
        if (aviso == null || !"payment".equals(aviso.path("type").asText())
                || !dataId.equals(aviso.path("data").path("id").asText())
                || aviso.path("live_mode").asBoolean(true))
            throw new WebhookInvalido(false);
        pagamentos.registrarNotificacao(requestId, dataId);
        return ResponseEntity.ok().build();
    }
}
