package br.com.balaio.commerce.notification.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.TokenInterno;

@RestController
public class EmailsInternosController {
    private final EmailsPedidoUseCase emails;
    private final TokenInterno tokenInterno;

    public EmailsInternosController(EmailsPedidoUseCase emails, TokenInterno tokenInterno) {
        this.emails = emails;
        this.tokenInterno = tokenInterno;
    }

    public record Reagendamento(int reagendados) { }

    @GetMapping("/internal/v1/pedidos/{id}/emails")
    public Envelope situacao(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        return new Envelope(emails.situacao(id), null);
    }

    @PostMapping("/internal/v1/pedidos/{id}/emails/reenviar")
    public Envelope reenviar(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        tokenInterno.exigir(token);
        return new Envelope(new Reagendamento(emails.reenviarFalhas(id)), null);
    }
}
