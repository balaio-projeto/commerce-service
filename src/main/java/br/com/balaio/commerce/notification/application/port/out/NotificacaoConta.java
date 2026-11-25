package br.com.balaio.commerce.notification.application.port.out;

import java.util.UUID;

public interface NotificacaoConta {
    void registrar(UUID conta, String tipo, String titulo, String mensagem, String link, String chave);
}
