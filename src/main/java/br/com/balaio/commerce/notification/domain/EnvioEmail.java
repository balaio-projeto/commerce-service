package br.com.balaio.commerce.notification.domain;

import java.util.UUID;

public record EnvioEmail(UUID pedido, String evento, String destinatario) {
    public boolean paraComprador() {
        return "comprador".equals(destinatario);
    }
}
