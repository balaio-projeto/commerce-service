package br.com.balaio.commerce.order.integration;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import br.com.balaio.commerce.order.application.port.out.PerfisCompradores;
import tools.jackson.databind.JsonNode;

@Component
public class PerfisCompradoresHttpAdapter implements PerfisCompradores {
    private static final Logger log = LoggerFactory.getLogger(PerfisCompradoresHttpAdapter.class);
    private static final int CONTAS_POR_CONSULTA_DE_IMAGENS = 100;
    private static final int TAMANHO_MINIMO_TOKEN = 32;

    private final RestClient http;
    private final String token;

    public PerfisCompradoresHttpAdapter(RestClient.Builder builder, @Value("${balaio.profile-url}") String profileUrl,
            @Value("${balaio.internal-token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = builder.baseUrl(profileUrl).requestFactory(factory).build();
        this.token = token == null ? "" : token;
    }

    @Override
    public Map<UUID, PerfilComprador> consultar(Collection<UUID> compradores) {
        var unicos = compradores.stream().filter(Objects::nonNull).distinct().toList();
        if (unicos.isEmpty() || token.length() < TAMANHO_MINIMO_TOKEN) return Map.of();
        var nomes = nomes(unicos);
        var imagens = imagens(unicos);
        var perfis = new HashMap<UUID, PerfilComprador>();
        for (UUID comprador : unicos) {
            perfis.put(comprador, new PerfilComprador(nomes.get(comprador), imagens.get(comprador)));
        }
        return perfis;
    }

    private Map<UUID, String> nomes(List<UUID> compradores) {
        var nomes = new HashMap<UUID, String>();
        for (UUID comprador : compradores) {
            try {
                JsonNode resposta = http.get().uri("/internal/v1/contas/{id}/contato", comprador)
                        .header("X-Balaio-Service-Token", token).retrieve().body(JsonNode.class);
                String nome = resposta == null ? "" : resposta.path("dados").path("nome").asString("");
                if (!nome.isBlank()) nomes.put(comprador, nome.trim());
            } catch (HttpClientErrorException.NotFound contaRemovida) {
                log.debug("Conta do comprador não encontrada no perfil.");
            } catch (RestClientException indisponivel) {
                log.warn("Nomes dos compradores indisponíveis no perfil: {}", indisponivel.getClass().getSimpleName());
                return nomes;
            }
        }
        return nomes;
    }

    private Map<UUID, String> imagens(List<UUID> compradores) {
        var imagens = new HashMap<UUID, String>();
        for (int inicio = 0; inicio < compradores.size(); inicio += CONTAS_POR_CONSULTA_DE_IMAGENS) {
            var lote = compradores.subList(inicio, Math.min(compradores.size(), inicio + CONTAS_POR_CONSULTA_DE_IMAGENS));
            try {
                JsonNode resposta = http.post().uri("/internal/v1/contas/imagens")
                        .header("X-Balaio-Service-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("contas", lote))
                        .retrieve().body(JsonNode.class);
                JsonNode dados = resposta == null ? null : resposta.path("dados");
                if (dados == null) continue;
                for (UUID comprador : lote) {
                    String imagem = dados.path(comprador.toString()).asString("");
                    if (!imagem.isBlank()) imagens.put(comprador, imagem);
                }
            } catch (RestClientException indisponivel) {
                log.warn("Fotos dos compradores indisponíveis no perfil: {}", indisponivel.getClass().getSimpleName());
                return imagens;
            }
        }
        return imagens;
    }
}
