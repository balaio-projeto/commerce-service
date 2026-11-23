package br.com.balaio.commerce.notification.integration;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.notification.application.port.out.ContatoConta;
import tools.jackson.databind.JsonNode;

@Component
public class ContatoContaHttpAdapter implements ContatoConta {
    private final RestClient http;
    private final String token;

    public ContatoContaHttpAdapter(RestClient.Builder builder, @Value("${balaio.profile-url}") String profileUrl,
            @Value("${balaio.internal-token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.baseUrl(profileUrl).requestFactory(factory).build();
        this.token = token;
    }

    @Override
    public Contato consultar(UUID conta) {
        if (token.length() < 32) throw new IllegalStateException("Token interno não configurado.");
        JsonNode resposta = http.get().uri("/internal/v1/contas/{id}/contato", conta)
                .header("X-Balaio-Service-Token", token).retrieve().body(JsonNode.class);
        JsonNode dados = resposta == null ? null : resposta.path("dados");
        if (dados == null || !dados.hasNonNull("email") || dados.path("email").asString().isBlank())
            throw new IllegalStateException("Contato da conta indisponível.");
        return new Contato(dados.path("nome").asString(""), dados.path("email").asString());
    }
}
