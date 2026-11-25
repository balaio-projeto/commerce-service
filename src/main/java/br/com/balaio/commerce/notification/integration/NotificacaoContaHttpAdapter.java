package br.com.balaio.commerce.notification.integration;

import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.notification.application.port.out.NotificacaoConta;

@Component
public class NotificacaoContaHttpAdapter implements NotificacaoConta {
    private final RestClient http;
    private final String token;

    public NotificacaoContaHttpAdapter(RestClient.Builder builder, @Value("${balaio.profile-url}") String profileUrl,
            @Value("${balaio.internal-token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.clone().baseUrl(profileUrl).requestFactory(factory).build();
        this.token = token;
    }

    @Override
    public void registrar(UUID conta, String tipo, String titulo, String mensagem, String link, String chave) {
        if (token.length() < 32) throw new IllegalStateException("Token interno não configurado.");
        var corpo = new HashMap<String, String>();
        corpo.put("conta", conta.toString());
        corpo.put("tipo", tipo);
        corpo.put("titulo", titulo);
        corpo.put("mensagem", mensagem);
        corpo.put("link", link);
        corpo.put("chave", chave);
        http.post().uri("/internal/v1/notificacoes").header("X-Balaio-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON).body(corpo).retrieve().toBodilessEntity();
    }
}
