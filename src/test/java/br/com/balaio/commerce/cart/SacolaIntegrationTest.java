package br.com.balaio.commerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.balaio.commerce.order.CatalogoCliente;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "balaio.worker.enabled=false", "payment.mercadopago.worker-enabled=false"})
@Import(SacolaIntegrationTest.Postgres.class)
class SacolaIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean JwtDecoder decoder;
    private final HttpClient http = HttpClient.newHttpClient();

    private Jwt jwt(String token, UUID comprador, String papel) {
        return Jwt.withTokenValue(token).header("alg", "RS256").subject(comprador.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("papel", papel).build();
    }

    private HttpResponse<String> chamar(String metodo, String token, String corpo) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1/estado/sacola"))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("PUT".equals(metodo)) builder.PUT(HttpRequest.BodyPublishers.ofString(corpo));
        else builder.GET();
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void isolaCompradoresValidaCatalogoEConservaSacolaEmConflito() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(decoder.decode("comprador-a")).thenReturn(jwt("comprador-a", a, "comprador"));
        when(decoder.decode("comprador-a-outra-sessao")).thenReturn(jwt("comprador-a-outra-sessao", a, "comprador"));
        when(decoder.decode("comprador-b")).thenReturn(jwt("comprador-b", b, "comprador"));
        when(decoder.decode("artesao")).thenReturn(jwt("artesao", UUID.randomUUID(), "artesao"));
        when(catalogo.cotar("peca-unica")).thenReturn(new CatalogoCliente.Cotacao(
                "peca-unica", new BigDecimal("12.00"), "unica", "ateliê", UUID.randomUUID()));
        when(catalogo.cotar("peca-comum")).thenReturn(new CatalogoCliente.Cotacao(
                "peca-comum", new BigDecimal("35.00"), "disponivel", "ateliê", UUID.randomUUID()));
        when(catalogo.cotar("sem-rede")).thenThrow(new ResourceAccessException("timeout"));
        when(catalogo.cotar("peca-legada")).thenReturn(new CatalogoCliente.Cotacao(
                "peca-legada", new BigDecimal("15.00"), "disponivel", "ateliê", null));

        assertThat(chamar("GET", null, "").statusCode()).isEqualTo(401);
        assertThat(chamar("GET", "artesao", "").statusCode()).isEqualTo(403);
        assertThat(chamar("GET", "comprador-a", "").body()).contains("\"dados\":[]");
        var corpo = "{\"sacola\":[{\"slug\":\"peca-unica\",\"quantidade\":1},{\"slug\":\"peca-comum\",\"quantidade\":3}]}";
        assertThat(chamar("PUT", "comprador-a", corpo).statusCode()).isEqualTo(200);
        assertThat(chamar("PUT", "comprador-a", corpo).statusCode()).isEqualTo(200);
        assertThat(chamar("GET", "comprador-a", "").body()).contains("peca-unica", "peca-comum");
        assertThat(chamar("GET", "comprador-a-outra-sessao", "").body()).contains("peca-unica", "peca-comum");
        assertThat(chamar("GET", "comprador-b", "").body()).contains("\"dados\":[]");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sacola_itens WHERE comprador_id = ?", Integer.class, a))
                .isEqualTo(2);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[{\"slug\":\"peca-unica\",\"quantidade\":2}]}" ).statusCode()).isEqualTo(409);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[{\"slug\":\"sem-rede\",\"quantidade\":1}]}" ).statusCode()).isEqualTo(503);
        assertThat(chamar("PUT", "comprador-a", "{\"compradorId\":\"" + b + "\",\"sacola\":[]}" ).statusCode()).isEqualTo(400);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[{\"slug\":\"peca-comum\",\"quantidade\":1,\"preco\":0}]}" ).statusCode()).isEqualTo(400);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[{\"slug\":\"peca-comum\",\"quantidade\":4294967297}]}" ).statusCode()).isEqualTo(400);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[{\"slug\":\"peca-comum\",\"quantidade\":1},{\"slug\":\"peca-comum\",\"quantidade\":1}]}" ).statusCode()).isEqualTo(400);
        assertThat(chamar("GET", "comprador-a", "").body()).contains("peca-unica", "peca-comum");
        assertThat(chamar("PUT", "comprador-b", "{\"sacola\":[{\"slug\":\"peca-comum\",\"quantidade\":1}]}" ).statusCode()).isEqualTo(200);
        assertThat(chamar("PUT", "comprador-a", "{\"sacola\":[]}" ).statusCode()).isEqualTo(200);
        assertThat(chamar("GET", "comprador-a", "").body()).contains("\"dados\":[]");
        assertThat(chamar("GET", "comprador-b", "").body()).contains("peca-comum");
        assertThat(chamar("PUT", "comprador-b", "{\"sacola\":[{\"slug\":\"peca-legada\",\"quantidade\":1}]}" ).statusCode()).isEqualTo(200);
    }
}
