package br.com.balaio.commerce.order;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class EnderecoSalvoClient {
    private final RestClient http;

    public EnderecoSalvoClient(@Value("${balaio.profile-url}") String profileUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(profileUrl).requestFactory(factory).build();
    }

    public Map<String, String> consultar(UUID id, String token) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resposta = http.get()
                    .uri("/api/v1/usuario/enderecos/{id}", id)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve().body(Map.class);
            if (resposta == null || !(resposta.get("dados") instanceof Map<?, ?> dados))
                throw new PedidoException(503, "PERFIL_INDISPONIVEL", "Resposta inválida do profile-service.");
            String rua = texto(dados.get("rua"));
            String cep = texto(dados.get("cep"));
            String bairroCompleto = texto(dados.get("bairro"));
            String[] partes = Arrays.stream(bairroCompleto.split(",")).map(String::trim).toArray(String[]::new);
            if (partes.length < 2)
                throw new PedidoException(422, "ENDERECO_INCOMPLETO",
                        "Complete cidade e estado no endereço salvo antes do checkout.");
            String estado = partes[partes.length - 1].toUpperCase();
            String cidade = partes[partes.length - 2];
            String bairro = String.join(", ", Arrays.copyOf(partes, partes.length - 2));
            return Map.of("cep", cep, "endereco", bairro.isBlank() ? rua : rua + ", " + bairro,
                    "cidade", cidade, "estado", estado);
        } catch (HttpClientErrorException.NotFound erro) {
            throw new PedidoException(404, "ENDERECO_NAO_ENCONTRADO", "Endereço salvo não encontrado.");
        } catch (HttpClientErrorException.Forbidden erro) {
            throw new PedidoException(403, "SEM_PERMISSAO", "Endereço salvo indisponível para esta conta.");
        } catch (RestClientException erro) {
            throw new PedidoException(503, "PERFIL_INDISPONIVEL", "Não foi possível consultar o endereço salvo.");
        }
    }

    private String texto(Object valor) {
        if (!(valor instanceof String texto) || texto.isBlank())
            throw new PedidoException(422, "ENDERECO_INCOMPLETO", "Endereço salvo incompleto.");
        return texto.trim();
    }
}
