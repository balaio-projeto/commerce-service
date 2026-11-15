package br.com.balaio.commerce.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
public class PaymentWebhookController {
    private final CheckoutProService pagamentos;

    public PaymentWebhookController(CheckoutProService pagamentos) {
        this.pagamentos = pagamentos;
    }

    @PostMapping("/api/v1/pagamentos/mercado-pago/notificacoes")
    public ResponseEntity<Void> receber(@RequestHeader(value = "x-signature", required = false) String assinatura,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(name = "data.id", required = false) String dataId,
            @RequestBody JsonNode aviso) {
        pagamentos.receber(assinatura, requestId, dataId, aviso);
        return ResponseEntity.ok().build();
    }
}
