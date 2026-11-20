package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import br.com.balaio.commerce.payment.CheckoutProClient;
import br.com.balaio.commerce.payment.CheckoutProService;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "balaio.worker.enabled=false", "payment.mercadopago.worker-enabled=false",
        "payment.mercadopago.environment=sandbox",
        "payment.mercadopago.access-token=APP_USR-test-placeholder",
        "payment.mercadopago.webhook-secret=webhook-test-placeholder",
        "payment.mercadopago.notification-url=https://test.example/webhook",
        "payment.mercadopago.frontend-return-url=https://test.example"})
@org.springframework.context.annotation.Import(PedidosHttpPostgresTest.Postgres.class)
class PedidosHttpPostgresTest {
    private static final String ISSUER = "https://profile.example.test";
    private static final String AUDIENCE = "balaio-api-test";
    private static final KeyPair KEYS = chaves();
    private static final Path PUBLIC_KEY = chavePublica();

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry properties) {
        properties.add("balaio.jwt.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        properties.add("balaio.jwt.issuer", () -> ISSUER);
        properties.add("balaio.jwt.audience", () -> AUDIENCE);
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired PedidoService pedidos;
    @Autowired CheckoutProService pagamentos;
    @Autowired ObjectMapper json;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean CheckoutProClient provedor;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void rotasHttpValidamJwtPapelTitularidadeEstadoEEnvelope() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID outro = UUID.randomUUID();
        String slug = "pedido-http-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(
                slug, new BigDecimal("50.00"), "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)),
                "padrao", "checkout_pro", Map.of("cep", "52021030", "endereco", "Rua de teste",
                        "cidade", "Recife", "estado", "PE")), comprador, slug);
        UUID reserva = UUID.randomUUID();
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("50.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        pedidos.processar(id);
        String pagamentoId = "pagamento-" + slug;
        BigDecimal total = (BigDecimal) pedidos.consultarInterno(id).get("total");
        when(provedor.pagamento(pagamentoId)).thenReturn(new CheckoutProClient.Pagamento(
                pagamentoId, id.toString(), total, "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new CheckoutProClient.Ordem(
                "pref-" + id, id.toString()));
        // A preferência é criada antes da confirmação do pagamento.
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenReturn(new CheckoutProClient.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id));
        pagamentos.preparar(id);
        pagamentos.aplicarPagamento(pagamentoId);

        String tokenArtesao = token(artesao, "artesao");
        String tokenOutro = token(outro, "artesao");
        String tokenComprador = token(comprador, "comprador");
        assertThat(chamar("GET", "/api/v1/artesao/pedidos-pendentes", null, null).statusCode()).isEqualTo(401);
        var lista = chamar("GET", "/api/v1/artesao/pedidos-pendentes?artesao=" + outro, tokenArtesao, null);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(json.readTree(lista.body()).path("dados").toString()).contains(id.toString(), slug);
        assertThat(json.readTree(chamar("GET", "/api/v1/artesao/pedidos-pendentes", tokenOutro, null).body())
                .path("dados").size()).isZero();
        assertThat(chamar("GET", "/api/v1/artesao/pedidos-pendentes", tokenComprador, null).statusCode())
                .isEqualTo(403);

        String caminho = "/api/v1/pedidos/" + id + "/estado";
        assertThat(chamar("PATCH", caminho, null, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(401);
        assertThat(chamar("PATCH", caminho, tokenComprador, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(403);
        assertThat(chamar("PATCH", caminho, tokenOutro, "{\"estado\":\"producao\",\"artesaoId\":\""
                + outro + "\"}").statusCode()).isEqualTo(403);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"enviado\"}").statusCode()).isEqualTo(400);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("confirmado");
        var alterado = chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"producao\"}");
        assertThat(alterado.statusCode()).isEqualTo(200);
        assertThat(json.readTree(alterado.body()).path("dados").path("estado").asText()).isEqualTo("producao");
        assertThat(json.readTree(alterado.body()).path("erro").isNull()).isTrue();
        assertThat(json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body())
                .path("dados").path("estado").asText()).isEqualTo("producao");
        assertThat(json.readTree(chamar("GET", "/api/v1/artesao/pedidos-pendentes", tokenArtesao, null).body())
                .path("dados").size()).isZero();
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(409);
    }

    private HttpResponse<String> chamar(String metodo, String caminho, String token, String corpo) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("PATCH".equals(metodo)) request.method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(UUID subject, String papel) throws Exception {
        long now = Instant.now().getEpochSecond();
        var claims = Map.<String, Object>of("sub", subject.toString(), "papel", papel, "iss", ISSUER,
                "aud", List.of(AUDIENCE), "iat", now - 5, "exp", now + 3600, "jti", UUID.randomUUID().toString());
        var jwt = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair chaves() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path chavePublica() {
        try {
            Path path = Files.createTempFile("commerce-pedidos-public-", ".pem");
            path.toFile().deleteOnExit();
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(path, pem, StandardCharsets.US_ASCII);
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
