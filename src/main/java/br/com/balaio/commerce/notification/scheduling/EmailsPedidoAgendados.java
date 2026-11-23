package br.com.balaio.commerce.notification.scheduling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase;

@Component
public class EmailsPedidoAgendados {
    private final EmailsPedidoUseCase emails;
    private final boolean ativo;

    public EmailsPedidoAgendados(EmailsPedidoUseCase emails,
            @Value("${balaio.mail.worker-enabled:${balaio.worker.enabled:true}}") boolean ativo) {
        this.emails = emails;
        this.ativo = ativo;
    }

    @Scheduled(fixedDelayString = "${balaio.mail.intervalo-ms:15000}", initialDelayString = "${balaio.mail.intervalo-ms:15000}")
    public void processar() {
        if (ativo) emails.processarPendentes();
    }
}
