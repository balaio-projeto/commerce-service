package br.com.balaio.commerce.notification.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import br.com.balaio.commerce.notification.application.port.out.RemetenteEmail;

@Component
public class SmtpRemetenteEmail implements RemetenteEmail {
    private final JavaMailSender smtp;
    private final String remetente;

    public SmtpRemetenteEmail(JavaMailSender smtp, @Value("${balaio.mail.from:nao-responda@localhost}") String remetente) {
        this.smtp = smtp;
        this.remetente = remetente;
    }

    @Override
    public void enviar(String destinatario, String assunto, String texto) {
        var mensagem = new SimpleMailMessage();
        mensagem.setFrom(remetente);
        mensagem.setTo(destinatario);
        mensagem.setSubject(assunto);
        mensagem.setText(texto);
        smtp.send(mensagem);
    }
}
