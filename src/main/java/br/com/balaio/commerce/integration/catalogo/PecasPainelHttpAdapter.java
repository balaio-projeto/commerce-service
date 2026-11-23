package br.com.balaio.commerce.integration.catalogo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.dashboard.application.port.out.PecasDoPainel;
import tools.jackson.databind.JsonNode;

@Component
public class PecasPainelHttpAdapter implements PecasDoPainel {
    private final RestClient http;

    public PecasPainelHttpAdapter(RestClient.Builder builder, @Value("${balaio.catalog-url}") String url,
            @Value("${balaio.painel.catalogo-timeout-ms:800}") long timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 500)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.http = builder.baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public List<PecaPainel> listar(UUID artesao, String tokenArtesao) {
        JsonNode resposta = http.get().uri("/api/v1/artesaos/{slug}/pecas/painel", "artesao-" + artesao)
                .headers(headers -> headers.setBearerAuth(tokenArtesao))
                .retrieve().body(JsonNode.class);
        if (resposta == null || !resposta.path("dados").isArray())
            throw new IllegalStateException("Resposta inválida do catálogo.");
        var pecas = new ArrayList<PecaPainel>();
        for (JsonNode peca : resposta.path("dados")) {
            String estado = peca.path("estadoVenda").asString("");
            if (estado.isBlank()) estado = peca.hasNonNull("inativadoEm") ? "inativada"
                    : "publicada".equals(peca.path("situacao").asString("")) ? "disponivel" : "rascunho";
            pecas.add(new PecaPainel(peca.path("slug").asString(), peca.path("nome").asString(), estado));
        }
        return pecas;
    }
}
