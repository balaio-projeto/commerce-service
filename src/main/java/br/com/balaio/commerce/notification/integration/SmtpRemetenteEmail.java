package br.com.balaio.commerce.notification.integration;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    public void enviar(String destinatario, String assunto, String texto, String html) {
        var mensagem = smtp.createMimeMessage();
        try {
            var ajudante = new MimeMessageHelper(mensagem, true, StandardCharsets.UTF_8.name());
            ajudante.setFrom(remetente);
            ajudante.setTo(destinatario);
            ajudante.setSubject(assunto);
            ajudante.setText(texto, html);
        } catch (MessagingException falha) {
            throw new MailPreparationException("Não foi possível montar o e-mail.", falha);
        }
        smtp.send(mensagem);
    }
}
