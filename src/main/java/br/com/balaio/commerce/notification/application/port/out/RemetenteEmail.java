package br.com.balaio.commerce.notification.application.port.out;

public interface RemetenteEmail {
    void enviar(String destinatario, String assunto, String texto, String html);
}
