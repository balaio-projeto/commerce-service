package br.com.balaio.commerce.order;

import br.com.balaio.commerce.order.application.PedidoException;

import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.PedidoService;

import br.com.balaio.commerce.integration.catalogo.CatalogoCliente;
import br.com.balaio.commerce.order.application.port.out.EnderecoSalvo;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;
import br.com.balaio.commerce.payment.application.CheckoutProService;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "balaio.worker.enabled=false", "payment.mercadopago.worker-enabled=false",
        "balaio.internal-token=12345678901234567890123456789012",
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
    @MockitoBean PagamentoGateway provedor;
    @MockitoBean EnderecoSalvo enderecos;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void rotasHttpValidamJwtPapelTitularidadeEstadoEEnvelope() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID outro = UUID.randomUUID();
        String slug = "pedido-http-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(
                slug, new BigDecimal("50.00"), "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of("cep", "52021030", "endereco", "Rua de teste",
                        "cidade", "Recife", "estado", "PE")), comprador, slug);
        UUID reserva = UUID.randomUUID();
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("50.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        pedidos.processar(id);
        String pagamentoId = "pagamento-" + slug;
        BigDecimal total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(
                pagamentoId, id.toString(), total, "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem(
                "pref-" + id, id.toString()));
        // A preferência é criada antes da confirmação do pagamento.
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenReturn(new PagamentoGateway.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id));
        pagamentos.preparar(id);
        pagamentos.aplicarPagamento(pagamentoId);

        String tokenArtesao = token(artesao, "artesao");
        String tokenOutro = token(outro, "artesao");
        String tokenComprador = token(comprador, "comprador");
        String tokenOutroComprador = token(UUID.randomUUID(), "comprador");
        var listaComprador = json.readTree(chamar("GET", "/api/v1/pedidos", tokenComprador, null).body()).path("dados");
        assertThat(listaComprador.size()).isEqualTo(1);
        assertThat(listaComprador.get(0).path("endereco").isMissingNode()).isTrue();
        assertThat(listaComprador.get(0).path("checkoutUrl").isNull()).isTrue();
        assertThat(listaComprador.get(0).path("estadoPagamento").asString()).isEqualTo("approved");
        assertThat(json.readTree(chamar("GET", "/api/v1/pedidos", tokenOutroComprador, null).body())
                .path("dados").size()).isZero();
        assertThat(chamar("GET", "/api/v1/pedidos/" + id, tokenOutroComprador, null).statusCode()).isEqualTo(404);
        var detalhe = json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body())
                .path("dados");
        assertThat(detalhe.path("endereco").path("cidade").asString()).isEqualTo("Recife");
        assertThat(detalhe.path("checkoutUrl").isNull()).isTrue();
        assertThat(detalhe.path("historicoInferido").isMissingNode()).isTrue();
        assertThat(detalhe.path("etapas").get(0).path("nota").isNull()).isTrue();
        assertThat(detalhe.path("itens").get(0).path("preco").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(detalhe.path("etapas").size()).isGreaterThanOrEqualTo(3);
        assertThat(detalhe.path("etapas").get(0).path("estado").asString()).isEqualTo("aguardando_reserva");
        String participantes = "/internal/v1/pedidos/" + id + "/participantes";
        assertThat(chamarInterno(participantes, null).statusCode()).isEqualTo(403);
        var partes = json.readTree(chamarInterno(participantes, "12345678901234567890123456789012").body())
                .path("dados");
        assertThat(partes.path("compradorId").asString()).isEqualTo(comprador.toString());
        assertThat(partes.path("artesaoId").asString()).isEqualTo(artesao.toString());
        assertThat(partes.size()).isEqualTo(2);
        var venda = json.readTree(chamar("GET", "/api/v1/artesao/vendas", tokenArtesao, null).body())
                .path("dados").get(0);
        assertThat(venda.path("endereco").isMissingNode()).isTrue();
        assertThat(venda.path("checkoutUrl").isMissingNode()).isTrue();
        assertThat(venda.path("estadoPagamento").isMissingNode()).isTrue();
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
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("confirmado");
        var alterado = chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"producao\"}");
        assertThat(alterado.statusCode()).isEqualTo(200);
        assertThat(json.readTree(alterado.body()).path("dados").path("estado").asText()).isEqualTo("producao");
        assertThat(json.readTree(alterado.body()).path("erro").isNull()).isTrue();
        assertThat(json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body())
                .path("dados").path("estado").asText()).isEqualTo("producao");
        var etapas = json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body())
                .path("dados").path("etapas");
        assertThat(etapas.get(etapas.size() - 1).path("estado").asString()).isEqualTo("producao");
        assertThat(json.readTree(chamar("GET", "/api/v1/artesao/pedidos-pendentes", tokenArtesao, null).body())
                .path("dados").size()).isZero();
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(409);
    }

    @Test
    void rotasInternasValidamTokenEReprocessamMesmoComWorkersDesativados() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "reprocessar-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("50.00"), "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua", "cidade", "Recife", "estado", "PE")), comprador, slug);
        String base = "/internal/v1/pedidos/" + id;
        String segredo = "12345678901234567890123456789012";
        String decisao = """
                {"decisaoId":"%s","decisao":"negar"}
                """.formatted(UUID.randomUUID());
        for (String rota : List.of(base, base + "/participantes")) {
            assertThat(chamarInterno(rota, null).statusCode()).isEqualTo(403);
            assertThat(chamarInterno(rota, "invalido").statusCode()).isEqualTo(403);
            assertThat(chamarInterno(rota, segredo).statusCode()).isEqualTo(200);
        }
        assertThat(chamarInterno(base + "/decisao-mediacao", null, decisao).statusCode()).isEqualTo(403);
        assertThat(chamarInterno(base + "/reprocessar", "invalido", "{}").statusCode()).isEqualTo(403);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("aguardando_reserva");
        var reprocessado = chamarInterno(base + "/reprocessar", segredo, "{}");
        assertThat(reprocessado.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reprocessado.body()).path("dados").path("id").asString()).isEqualTo(id.toString());
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("aguardando_pagamento");
        jdbc.update("UPDATE pedidos SET estado = 'confirmado' WHERE id = ?", id);
        assertThat(chamarInterno(base + "/decisao-mediacao", segredo, decisao).statusCode()).isEqualTo(200);
        assertThat(chamarInterno(base + "/decisao-mediacao", segredo, decisao).statusCode()).isEqualTo(200);

        jdbc.update("DELETE FROM pedido_etapas WHERE pedido_id = ?", id);
        var interno = json.readTree(chamarInterno(base, segredo).body()).path("dados");
        assertThat(interno.path("endereco").isMissingNode()).isTrue();
        assertThat(interno.path("checkoutUrl").isMissingNode()).isTrue();
        assertThat(interno.path("historicoInferido").asBoolean()).isTrue();
        var etapa = interno.path("etapas").get(0);
        assertThat(etapa.path("criadaEm").isMissingNode()).isTrue();
        assertThat(etapa.path("nota").isMissingNode()).isTrue();
        assertThat(etapa.path("atual").asBoolean()).isTrue();
    }

    @Test
    void checkoutSalvoViaHttpMantemReplayEWebhookRetorna200SemCorpo() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID enderecoId = UUID.randomUUID();
        String slug = "checkout-http-" + UUID.randomUUID();
        String jwt = token(comprador, "comprador");
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("40.00"), "encomenda", "atelie", UUID.randomUUID()));
        when(enderecos.consultar(enderecoId, jwt)).thenReturn(Map.of("cep", "52021030", "endereco", "Rua do perfil",
                "cidade", "Recife", "estado", "PE"));
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(chamada -> {
            UUID id = chamada.getArgument(0);
            return new PagamentoGateway.Preferencia("pref-" + id,
                    "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id);
        });
        String corpo = """
                {"itens":[{"slug":"%s","quantidade":1}],"freteId":"padrao","meio":"checkout_pro",
                 "enderecoId":"%s","endereco":{"cidade":"Ignorada"}}
                """.formatted(slug, enderecoId);
        var checkout = requisicao("/api/v1/checkout").header("Authorization", "Bearer " + jwt)
                .header("Idempotency-Key", slug).POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
        var primeiro = http.send(checkout, HttpResponse.BodyHandlers.ofString());
        var replay = http.send(checkout, HttpResponse.BodyHandlers.ofString());
        assertThat(primeiro.statusCode()).isEqualTo(202);
        assertThat(replay.statusCode()).isEqualTo(202);
        var dados = json.readTree(primeiro.body()).path("dados");
        assertThat(json.readTree(replay.body()).path("dados")).isEqualTo(dados);
        assertThat(dados.path("endereco").path("endereco").asString()).isEqualTo("Rua do perfil");
        assertThat(dados.path("checkoutUrl").asString()).startsWith("https://sandbox.mercadopago.com/");
        verify(enderecos).consultar(enderecoId, jwt);
        verify(provedor).criar(any(), any(), any());

        String requestId = UUID.randomUUID().toString();
        String pagamentoId = "382701";
        String ts = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("webhook-test-placeholder".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String manifesto = "id:" + pagamentoId + ";request-id:" + requestId + ";ts:" + ts + ";";
        String assinatura = HexFormat.of().formatHex(mac.doFinal(manifesto.getBytes(StandardCharsets.UTF_8)));
        var webhook = requisicao("/api/v1/pagamentos/mercado-pago/notificacoes?data.id=" + pagamentoId)
                .header("x-request-id", requestId).header("x-signature", "ts=" + ts + ",v1=" + assinatura)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"type":"payment","data":{"id":"382701"},"live_mode":false}
                        """)).build();
        for (int i = 0; i < 2; i++) {
            var resposta = http.send(webhook, HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(200);
            assertThat(resposta.body()).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_notificacoes "
                + "WHERE request_id = ? AND pagamento_id = ? AND processado_em IS NULL",
                Integer.class, requestId, pagamentoId)).isEqualTo(1);
    }

    @Test
    void recusaHttpAguardaEstornoEProducaoExigeMediacao() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "recusa-http-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("50.00"), "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("50.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(invocacao -> {
            UUID id = invocacao.getArgument(0);
            return new PagamentoGateway.Preferencia("pref-" + id,
                    "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id);
        });
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of("cep", "52021030", "endereco", "Rua",
                        "cidade", "Recife", "estado", "PE")), comprador, slug);
        pedidos.processar(id);
        pagamentos.preparar(id);
        String pagamentoId = "pagamento-" + slug;
        BigDecimal total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(
                pagamentoId, id.toString(), total, "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem("pref-" + id, id.toString()));
        pagamentos.aplicarPagamento(pagamentoId);

        String recusar = "/api/v1/pedidos/" + id + "/recusar";
        String corpo = "{\"motivo\":\"Não consigo produzir.\"}";
        String tokenArtesao = token(artesao, "artesao");
        assertThat(chamar("PATCH", recusar, null, corpo).statusCode()).isEqualTo(401);
        assertThat(chamar("PATCH", recusar, token(comprador, "comprador"), corpo).statusCode()).isEqualTo(403);
        assertThat(chamar("PATCH", recusar, token(UUID.randomUUID(), "artesao"), corpo).statusCode())
                .isEqualTo(403);
        assertThat(chamar("PATCH", recusar, tokenArtesao, "{\"motivo\":\"não\"}").statusCode())
                .isEqualTo(400);
        var pendente = chamar("PATCH", recusar, tokenArtesao, corpo);
        assertThat(pendente.statusCode()).isEqualTo(202);
        assertThat(json.readTree(pendente.body()).path("dados").path("estado").asString())
                .isEqualTo("reembolso_pendente");
        assertThat(chamar("PATCH", recusar, tokenArtesao, corpo).statusCode()).isEqualTo(202);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(id, reserva);
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(
                pagamentoId, id.toString(), total, "BRL", "refunded", false, "ordem-" + id, total));
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(chamar("PATCH", recusar, tokenArtesao, corpo).statusCode()).isEqualTo(200);
        assertThat(json.readTree(chamar("GET", "/api/v1/pedidos/" + id,
                token(comprador, "comprador"), null).body()).path("dados").path("estado").asString())
                .isEqualTo("recusado");
        org.mockito.Mockito.verify(catalogo).liberar(id, reserva);

        UUID segundo = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of("cep", "52021030", "endereco", "Rua",
                        "cidade", "Recife", "estado", "PE")), comprador, slug + "-segundo");
        pedidos.processar(segundo);
        // Estado avançado é usado para verificar o contrato HTTP de mediação.
        for (String estado : List.of("producao", "enviado", "entregue")) {
            jdbc.update("UPDATE pedidos SET estado = ? WHERE id = ?", estado, segundo);
            var mediacao = chamar("PATCH", "/api/v1/pedidos/" + segundo + "/cancelar",
                    token(comprador, "comprador"), "{}");
            assertThat(mediacao.statusCode()).isEqualTo(409);
            assertThat(json.readTree(mediacao.body()).path("erro").path("codigo").asString())
                    .isEqualTo("MEDIACAO_NECESSARIA");
        }
    }

    private HttpResponse<String> chamar(String metodo, String caminho, String token, String corpo) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("PATCH".equals(metodo)) request.method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> chamarInterno(String caminho, String token) throws Exception {
        return chamarInterno(caminho, token, null);
    }

    private HttpResponse<String> chamarInterno(String caminho, String token, String corpo) throws Exception {
        var request = requisicao(caminho);
        if (token != null) request.header("X-Balaio-Service-Token", token);
        if (corpo != null) request.POST(HttpRequest.BodyPublishers.ofString(corpo));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder requisicao(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .header("Content-Type", "application/json");
    }

    private static String token(UUID subject, String papel) throws Exception {
        long now = Instant.now().getEpochSecond();
        var claims = Map.<String, Object>of("sub", subject.toString(), "papel", papel, "iss", ISSUER,
                "aud", List.of(AUDIENCE), "iat", now - 5, "exp", now + 3600, "jti", UUID.randomUUID().toString());
        var jwt = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void reaproveitaFingerprintsLegadosDeEnderecoManualESalvoSemAcessarRede() {
        UUID comprador = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID salvo = UUID.randomUUID();
        UUID enderecoId = UUID.fromString("c6b0f824-4259-497f-84ca-625b6afbbc95");
        var endereco = new java.util.LinkedHashMap<String, String>();
        endereco.put("cidade", "Recife");
        endereco.put("cep", "50000-000");
        endereco.put("estado", "PE");
        endereco.put("endereco", "Rua A, 1");
        endereco.put("complemento", null);
        var itens = List.of(new Item("vaso-z", 1), new Item("vaso-a", 2));
        inserirCheckoutLegado(manual, comprador, "manual",
                "77faf39026459ae926a309ad37029a304aae8885020b4ea8b4a94124d496251d");
        inserirCheckoutLegado(salvo, comprador, "salvo",
                "21221bdc82a702c60f6e593b9d77df425fc7cb35e6cd1cd18e3c6c9ee384360e");

        assertThat(pedidos.checkout(new Checkout(itens, "padrao", "checkout_pro", endereco),
                comprador, "manual")).isEqualTo(manual);
        assertThat(pedidos.checkoutSalvo(new Checkout(itens, "padrao", "checkout_pro", Map.of(), enderecoId),
                comprador, "salvo", "token-comprador")).isEqualTo(salvo);
        assertThatThrownBy(() -> pedidos.checkout(new Checkout(itens.reversed(), "padrao", "checkout_pro", endereco),
                comprador, "manual")).isInstanceOfSatisfying(PedidoException.class,
                        erro -> assertThat(erro.codigo()).isEqualTo("CHECKOUT_CONFLITANTE"));
        org.mockito.Mockito.verifyNoInteractions(enderecos, catalogo);
    }

    private void inserirCheckoutLegado(UUID id, UUID comprador, String chave, String fingerprint) {
        jdbc.update("""
                INSERT INTO pedidos(id, comprador_id, artesao_id, estado, chave_checkout, fingerprint_checkout,
                                    endereco, frete_id, frete, total, meio)
                VALUES (?, ?, ?, 'aguardando_pagamento', ?, ?, '{}', 'padrao', 38.90, 58.90, 'checkout_pro')
                """, id, comprador, UUID.randomUUID(), chave, fingerprint);
    }

    @Test
    void snapshotDoPedidoNaoMudaComEdicaoOuRemocaoDoPerfilERepeticaoNaoConsultaPerfil() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID enderecoId = UUID.randomUUID();
        String slug = "snapshot-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(
                slug, new BigDecimal("50.00"), "encomenda", "atelie", artesao));
        when(enderecos.consultar(enderecoId, "token-comprador")).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("cep", "50000-000", "endereco", "Rua A, 1, Boa Vista", "cidade", "Recife", "estado", "PE");
        });
        var comando = new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of(), enderecoId);
        String chave = "chave-" + UUID.randomUUID();

        UUID pedido = pedidos.checkoutSalvo(comando, comprador, chave, "token-comprador");

        // Perfil editado depois do pedido: o snapshot gravado não pode acompanhar a mudança.
        when(enderecos.consultar(enderecoId, "token-comprador")).thenReturn(Map.of(
                "cep", "53000-000", "endereco", "Rua Nova, 9, Casa Caiada", "cidade", "Olinda", "estado", "PE"));
        UUID repetido = pedidos.checkoutSalvo(comando, comprador, chave, "token-comprador");

        // Endereço removido do perfil: a repetição com a mesma chave ainda devolve o pedido.
        when(enderecos.consultar(enderecoId, "token-comprador")).thenThrow(
                new PedidoException(PedidoException.Tipo.ENDERECO_NAO_ENCONTRADO, "Endereço salvo não encontrado."));
        UUID removido = pedidos.checkoutSalvo(comando, comprador, chave, "token-comprador");

        assertThat(repetido).isEqualTo(pedido);
        assertThat(removido).isEqualTo(pedido);
        verify(enderecos, times(1)).consultar(enderecoId, "token-comprador");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE comprador_id = ?", Integer.class, comprador))
                .isEqualTo(1);
        var snapshot = json.readTree(jdbc.queryForObject(
                "SELECT endereco::text FROM pedidos WHERE id = ?", String.class, pedido));
        assertThat(snapshot.path("endereco").asString()).isEqualTo("Rua A, 1, Boa Vista");
        assertThat(snapshot.path("cidade").asString()).isEqualTo("Recife");
        assertThat(snapshot.path("cep").asString()).isEqualTo("50000-000");
    }

    @Test
    void enderecoDeOutroCompradorNaoGeraPedido() {
        UUID comprador = UUID.randomUUID();
        UUID enderecoDeOutro = UUID.randomUUID();
        String slug = "intruso-" + UUID.randomUUID();
        when(enderecos.consultar(eq(enderecoDeOutro), anyString())).thenThrow(
                new PedidoException(PedidoException.Tipo.ENDERECO_NAO_ENCONTRADO, "Endereço salvo não encontrado."));
        var comando = new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of(), enderecoDeOutro);

        assertThatThrownBy(() -> pedidos.checkoutSalvo(comando, comprador, "chave-" + UUID.randomUUID(), "token-comprador"))
                .isInstanceOfSatisfying(PedidoException.class,
                        erro -> assertThat(erro.codigo()).isEqualTo("ENDERECO_NAO_ENCONTRADO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE comprador_id = ?", Integer.class, comprador))
                .isZero();
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
