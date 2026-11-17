package br.com.balaio.commerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
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

    private HttpResponse<String> revalidar(String token, String corpo) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1/sacola/revalidar"))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void revalidaPrecoDisponibilidadeIsolamentoEFalhaSemAlterarSacola() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(decoder.decode("revalidar-a")).thenReturn(jwt("revalidar-a", a, "comprador"));
        when(decoder.decode("revalidar-b")).thenReturn(jwt("revalidar-b", b, "comprador"));
        when(decoder.decode("revalidar-artesao")).thenReturn(jwt("revalidar-artesao", UUID.randomUUID(), "artesao"));
        jdbc.update("INSERT INTO sacola_itens(comprador_id, slug, quantidade) VALUES (?, ?, ?), (?, ?, ?)",
                a, "preco-alterado", 2, a, "peca-excluida", 1);
        var antiga = new CatalogoCliente.Cotacao("preco-alterado", new BigDecimal("42.50"),
                "disponivel", "atelie", UUID.randomUUID());
        var nova = new CatalogoCliente.Cotacao("preco-alterado", new BigDecimal("55.00"),
                "disponivel", "atelie", UUID.randomUUID());
        when(catalogo.cotar("preco-alterado")).thenReturn(antiga, nova, nova);
        when(catalogo.cotar("peca-excluida")).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThat(revalidar(null, "{}").statusCode()).isEqualTo(401);
        assertThat(revalidar("revalidar-artesao", "{}").statusCode()).isEqualTo(403);
        assertThat(revalidar("revalidar-b", "{}").body()).contains("\"itens\":[]", "\"podeProsseguir\":false");
        var primeira = revalidar("revalidar-a", "{\"compradorId\":\"" + b + "\",\"preco\":0}");
        assertThat(primeira.statusCode()).isEqualTo(200);
        assertThat(primeira.body()).contains("\"precoAtual\":42.50", "\"quantidade\":2",
                "\"situacao\":\"indisponivel\"", "\"podeProsseguir\":false", "\"revalidadoEm\":");
        assertThat(revalidar("revalidar-a", "{}").body()).contains("\"precoAtual\":55.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sacola_itens WHERE comprador_id = ?", Integer.class, a))
                .isEqualTo(2);
        assertThat(chamar("GET", "revalidar-a", "").body()).contains("preco-alterado", "peca-excluida")
                .doesNotContain("precoAtual");
        verify(catalogo, never()).reservar(any(), any());

        doThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8)).when(catalogo).cotar("peca-excluida");
        assertThat(revalidar("revalidar-a", "{}").body())
                .contains("\"situacao\":\"indisponivel\"", "\"podeProsseguir\":false");

        when(catalogo.cotar("preco-alterado")).thenThrow(new ResourceAccessException("timeout"));
        var falha = revalidar("revalidar-a", "{}");
        assertThat(falha.statusCode()).isEqualTo(503);
        assertThat(falha.body()).contains("CATALOGO_INDISPONIVEL", "\"dados\":null")
                .doesNotContain("podeProsseguir");
    }

    @Test
    void cotacaoHttpMalformadaDevolve503SemResultadoParcial() throws Exception {
        UUID comprador = UUID.randomUUID();
        when(decoder.decode("revalidar-malformada")).thenReturn(jwt("revalidar-malformada", comprador, "comprador"));
        jdbc.update("INSERT INTO sacola_itens(comprador_id, slug, quantidade) VALUES (?, ?, ?), (?, ?, ?)",
                comprador, "a-valida", 1, comprador, "z-malformada", 1);
        when(catalogo.cotar("a-valida")).thenReturn(new CatalogoCliente.Cotacao(
                "a-valida", new BigDecimal("20.00"), "disponivel", "atelie", UUID.randomUUID()));

        var conteudo = new AtomicReference<String>();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/v1/pecas/z-malformada/cotacao", requisicao -> {
            byte[] bytes = conteudo.get().getBytes(StandardCharsets.UTF_8);
            requisicao.getResponseHeaders().set("Content-Type", "application/json");
            requisicao.sendResponseHeaders(200, bytes.length);
            requisicao.getResponseBody().write(bytes);
            requisicao.close();
        });
        servidor.start();
        try {
            var cliente = new CatalogoCliente("http://127.0.0.1:" + servidor.getAddress().getPort(),
                    "token-interno-de-teste-123456789012");
            when(catalogo.cotar("z-malformada")).thenAnswer(chamada -> cliente.cotar(chamada.getArgument(0)));
            for (String resposta : new String[] {
                    "{\"dados\":{\"slug\":\"z-malformada\",\"preco\":\"invalido\",\"disponibilidade\":\"disponivel\",\"artesao\":\"atelie\"},\"erro\":null}",
                    "{\"dados\":null,\"erro\":null}",
                    "{\"dados\":{\"slug\":\"z-malformada\",\"preco\":\"12.00\",\"disponibilidade\":\"disponivel\",\"artesao\":\"atelie\",\"proprietarioId\":\"id-invalido\"},\"erro\":null}"
            }) {
                conteudo.set(resposta);
                var falha = revalidar("revalidar-malformada", "{}");
                assertThat(falha.statusCode()).isEqualTo(503);
                assertThat(falha.body()).contains("\"dados\":null", "CATALOGO_INDISPONIVEL")
                        .doesNotContain("podeProsseguir", "a-valida");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sacola_itens WHERE comprador_id = ?",
                    Integer.class, comprador)).isEqualTo(2);
            verify(catalogo, never()).reservar(any(), any());
        } finally {
            servidor.stop(0);
        }
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
