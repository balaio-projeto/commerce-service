package br.com.balaio.commerce.notification.application.port.out;

import java.util.UUID;

public interface ContatoConta {
    Contato consultar(UUID conta);

    record Contato(String nome, String email) { }
}
