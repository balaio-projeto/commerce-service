package br.com.balaio.commerce.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
public class CatalogoCliente {
    private final RestClient http;
    private final String token;

    public CatalogoCliente(@Value("${balaio.catalog-url}") String url,
            @Value("${balaio.internal-token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.token = token;
    }

    public record Cotacao(String slug, BigDecimal preco, String disponibilidade, String artesao,
                          UUID proprietarioId) { }
    public record Reserva(UUID id, String estado, BigDecimal preco, UUID proprietarioId) { }

    public Cotacao cotar(String slug) {
        JsonNode dados = dados(http.get().uri("/internal/v1/pecas/{slug}/cotacao", slug)
                .header("X-Balaio-Service-Token", segredo()).retrieve().body(JsonNode.class));
        return new Cotacao(dados.path("slug").asText(), new BigDecimal(dados.path("preco").asText()),
                dados.path("disponibilidade").asText(), dados.path("artesao").asText(),
                UUID.fromString(dados.path("proprietarioId").asText()));
    }

    public Reserva reservar(UUID pedido, String slug) {
        JsonNode dados = dados(http.post().uri("/internal/v1/reservas")
                .header("X-Balaio-Service-Token", segredo())
                .body(Map.of("pedidoId", pedido.toString(), "slug", slug,
                        "chave", "reservar:" + pedido + ":" + slug))
                .retrieve().body(JsonNode.class));
        return new Reserva(UUID.fromString(dados.path("id").asText()), dados.path("estado").asText(),
                new BigDecimal(dados.path("preco").asText()), UUID.fromString(dados.path("proprietarioId").asText()));
    }

    public String confirmar(UUID pedido, UUID reserva) {
        return operar(pedido, reserva, "confirmar");
    }

    public String liberar(UUID pedido, UUID reserva) {
        return operar(pedido, reserva, "liberar");
    }

    private String operar(UUID pedido, UUID reserva, String acao) {
        JsonNode dados = dados(http.post().uri("/internal/v1/reservas/{id}/" + acao, reserva)
                .header("X-Balaio-Service-Token", segredo())
                .body(Map.of("pedidoId", pedido.toString(), "chave", acao + ":" + reserva))
                .retrieve().body(JsonNode.class));
        return dados.path("estado").asText();
    }

    private String segredo() {
        if (token.length() < 32) throw new IllegalStateException("Token interno não configurado.");
        return token;
    }

    private JsonNode dados(JsonNode resposta) {
        if (resposta == null || resposta.path("dados").isMissingNode() || resposta.path("dados").isNull())
            throw new IllegalStateException("Resposta inválida do catálogo.");
        return resposta.path("dados");
    }
}
