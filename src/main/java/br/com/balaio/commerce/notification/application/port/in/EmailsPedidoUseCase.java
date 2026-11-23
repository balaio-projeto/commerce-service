package br.com.balaio.commerce.notification.application.port.in;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface EmailsPedidoUseCase {
    int processarPendentes();
    List<SituacaoEmail> situacao(UUID pedido);
    int reenviarFalhas(UUID pedido);

    record SituacaoEmail(String evento, String destinatario, String estado, int tentativas, String ultimoErro,
            OffsetDateTime proximaTentativaEm, OffsetDateTime enviadoEm) { }
}
