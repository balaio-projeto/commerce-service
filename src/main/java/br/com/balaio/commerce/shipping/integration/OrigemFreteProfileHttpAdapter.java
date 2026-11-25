package br.com.balaio.commerce.shipping.integration;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.shipping.application.port.out.OrigemFreteAtelie;
import br.com.balaio.commerce.shipping.domain.Cep;
import br.com.balaio.commerce.shipping.domain.OrigemAtelie;
import tools.jackson.databind.JsonNode;

@Component
public class OrigemFreteProfileHttpAdapter implements OrigemFreteAtelie {
    private static final Logger log = LoggerFactory.getLogger(OrigemFreteProfileHttpAdapter.class);

    private final RestClient http;
    private final String token;

    public OrigemFreteProfileHttpAdapter(RestClient.Builder builder, @Value("${balaio.profile-url}") String profileUrl,
            @Value("${balaio.internal-token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.baseUrl(profileUrl).requestFactory(factory).build();
        this.token = token == null ? "" : token;
    }

    @Override
    public Optional<OrigemAtelie> origem(UUID artesao) {
        if (artesao == null) return Optional.empty();
        if (token.length() < 32) {
            log.info("Token interno ausente; frete cotado a partir do CEP de origem padrão.");
            return Optional.empty();
        }
        try {
            JsonNode resposta = http.get().uri("/internal/v1/contas/{id}/origem-frete", artesao)
                    .header("X-Balaio-Service-Token", token).retrieve().body(JsonNode.class);
            JsonNode dados = resposta == null ? null : resposta.path("dados");
            if (dados == null || !dados.isObject()) return Optional.empty();
            JsonNode cep = dados.path("cepOrigem");
            Optional<String> cepNormalizado = cep.isString() ? Cep.normalizado(cep.asString()) : Optional.empty();
            if (cepNormalizado.isEmpty())
                log.debug("Ateliê sem CEP de origem configurado; frete cotado a partir do CEP de origem padrão.");
            return Optional.of(new OrigemAtelie(cepNormalizado.orElse(null), dados.path("aceitaRetirada").asBoolean(false),
                    dados.path("aceitaEntregaLocal").asBoolean(false), texto(dados, "retiradaBairro"),
                    texto(dados, "retiradaCidade")));
        } catch (HttpClientErrorException.NotFound naoEncontrado) {
            log.debug("Ateliê não encontrado no profile-service; frete cotado a partir do CEP de origem padrão.");
            return Optional.empty();
        } catch (RuntimeException falha) {
            log.info("Origem do frete indisponível no profile-service ({}); frete cotado a partir do CEP de origem padrão.",
                    falha.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String texto(JsonNode dados, String campo) {
        JsonNode valor = dados.path(campo);
        return valor.isString() && !valor.asString().isBlank() ? valor.asString().trim() : null;
    }
}
