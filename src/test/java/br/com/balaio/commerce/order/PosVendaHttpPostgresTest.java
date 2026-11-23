package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.balaio.commerce.dashboard.domain.PeriodoRelatorio;
import br.com.balaio.commerce.integration.catalogo.CatalogoCliente;
import br.com.balaio.commerce.notification.application.EmailPedidoService;
import br.com.balaio.commerce.notification.application.port.out.ContatoConta;
import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.application.PedidoService;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.payment.application.CheckoutProService;
import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;
import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.port.out.OrigemFreteAtelie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "balaio.worker.enabled=false", "payment.mercadopago.worker-enabled=false",
        "balaio.internal-token=12345678901234567890123456789012",
        "payment.mercadopago.environment=sandbox",
        "payment.mercadopago.access-token=APP_USR-test-placeholder",
        "payment.mercadopago.webhook-secret=webhook-test-placeholder",
        "payment.mercadopago.notification-url=https://test.example/webhook",
        "payment.mercadopago.frontend-return-url=https://test.example",
        "frete.melhor-envio.token=token-sandbox-de-teste",
        "frete.melhor-envio.cep-origem=50030-230",
        "balaio.painel.catalogo-timeout-ms=600"})
@Import(PosVendaHttpPostgresTest.Postgres.class)
class PosVendaHttpPostgresTest {
    private static final String ISSUER = "https://profile.example.test";
    private static final String AUDIENCE = "balaio-api-test";
    private static final String TOKEN_INTERNO = "12345678901234567890123456789012";
    private static final KeyPair KEYS = chaves();
    private static final Path PUBLIC_KEY = chavePublica();
    private static final AtomicReference<String> MODO_CATALOGO = new AtomicReference<>("ok");
    private static final AtomicReference<String> PECAS_PAINEL = new AtomicReference<>("[]");
    private static final List<String> CORPOS_MELHOR_ENVIO = new ArrayList<>();
    private static final AtomicReference<String> AUTORIZACAO_MELHOR_ENVIO = new AtomicReference<>();
    private static final HttpServer EXTERNO = servidorExterno();

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + EXTERNO.getAddress().getPort();
        properties.add("balaio.jwt.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        properties.add("balaio.jwt.issuer", () -> ISSUER);
        properties.add("balaio.jwt.audience", () -> AUDIENCE);
        properties.add("balaio.catalog-url", () -> base);
        properties.add("frete.melhor-envio.base-url", () -> base);
    }

    @AfterAll
    static void pararServidor() {
        EXTERNO.stop(0);
    }

    @Value("${local.server.port}") int port;
    @Autowired PedidoService pedidos;
    @Autowired CheckoutProService pagamentos;
    @Autowired EmailPedidoService emails;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean PagamentoGateway provedor;
    @MockitoBean JavaMailSender smtp;
    @MockitoBean ContatoConta contatos;
    @MockitoBean OrigemFreteAtelie origensFrete;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void preparar() {
        MODO_CATALOGO.set("ok");
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(chamada -> {
            UUID id = chamada.getArgument(0);
            return new PagamentoGateway.Preferencia("pref-" + id,
                    "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id);
        });
        when(contatos.consultar(any())).thenAnswer(chamada ->
                new ContatoConta.Contato("Pessoa", chamada.getArgument(0) + "@teste.local"));
    }

    @Test
    void artesaoDonoAvancaAteEntregueComRastreioEtapasEEmailsIdempotentes() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID id = pedidoConfirmado(comprador, artesao, "jarra-envio-" + UUID.randomUUID(), "me-2");
        String caminho = "/api/v1/pedidos/" + id + "/estado";
        String tokenArtesao = token(artesao, "artesao");
        String tokenComprador = token(comprador, "comprador");

        assertThat(chamar("PATCH", caminho, token(UUID.randomUUID(), "artesao"), "{\"estado\":\"producao\"}").statusCode())
                .isEqualTo(403);
        assertThat(chamar("PATCH", caminho, tokenComprador, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(403);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"entregue\"}").statusCode()).isEqualTo(409);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"producao\"}").statusCode()).isEqualTo(200);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"enviado\"}").statusCode()).isEqualTo(400);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"enviado\",\"rastreio\":\"a b\"}").statusCode())
                .isEqualTo(400);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("producao");

        var enviado = chamar("PATCH", caminho, tokenArtesao,
                "{\"estado\":\"enviado\",\"rastreio\":\" me123456789br \",\"transportadora\":\"Correios\"}");
        assertThat(enviado.statusCode()).isEqualTo(200);
        assertThat(json.readTree(enviado.body()).path("dados").path("rastreio").asString()).isEqualTo("ME123456789BR");
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"enviado\",\"rastreio\":\"OUTRO12345\"}")
                .statusCode()).isEqualTo(409);

        JsonNode detalhe = json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body()).path("dados");
        assertThat(detalhe.path("estado").asString()).isEqualTo("enviado");
        assertThat(detalhe.path("rastreio").asString()).isEqualTo("ME123456789BR");
        assertThat(detalhe.path("transportadora").asString()).isEqualTo("Correios");
        assertThat(detalhe.path("linkRastreio").asString())
                .isEqualTo("https://www.melhorrastreio.com.br/rastreio/ME123456789BR");
        assertThat(detalhe.path("freteProvedor").asString()).isEqualTo("melhor_envio_sandbox");
        assertThat(detalhe.path("previsaoEntrega").asString()).isEqualTo("1 a 2 dias úteis após o envio");
        var etapaEnvio = ultimaEtapa(detalhe);
        assertThat(etapaEnvio.path("estado").asString()).isEqualTo("enviado");
        assertThat(etapaEnvio.path("nota").asString()).isEqualTo("Código de rastreio: ME123456789BR (Correios)");

        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"entregue\"}").statusCode()).isEqualTo(200);
        assertThat(chamar("PATCH", caminho, tokenArtesao, "{\"estado\":\"entregue\"}").statusCode()).isEqualTo(409);
        detalhe = json.readTree(chamar("GET", "/api/v1/pedidos/" + id, tokenComprador, null).body()).path("dados");
        assertThat(detalhe.path("estado").asString()).isEqualTo("entregue");
        assertThat(detalhe.path("etapas").findValuesAsString("estado"))
                .containsSubsequence("aguardando_reserva", "confirmado", "producao", "enviado", "entregue");
        assertThat(chamar("PATCH", "/api/v1/pedidos/" + id + "/cancelar", tokenComprador, "{}").statusCode())
                .isEqualTo(409);

        assertThat(jdbc.queryForList("SELECT evento || ':' || destinatario FROM pedido_emails WHERE pedido_id = ? "
                + "ORDER BY evento, destinatario", String.class, id)).containsExactly(
                "entregue:comprador", "enviado:comprador", "pagamento_confirmado:artesao",
                "pagamento_confirmado:comprador");
        jdbc.update("UPDATE pedido_emails SET estado = 'enviado' WHERE pedido_id <> ?", id);
        doThrow(new MailSendException("smtp fora do ar")).when(smtp).send(any(SimpleMailMessage.class));
        assertThat(emails.processarPendentes()).isZero();
        var falhas = jdbc.queryForList("SELECT estado, tentativas, ultimo_erro FROM pedido_emails WHERE pedido_id = ?", id);
        assertThat(falhas).allSatisfy(linha -> {
            assertThat(linha.get("estado")).isEqualTo("pendente");
            assertThat(linha.get("tentativas")).isEqualTo(1);
            assertThat(linha.get("ultimo_erro")).isEqualTo("MailSendException");
        });
        assertThat(emails.processarPendentes()).isZero();
        reset(smtp);
        jdbc.update("UPDATE pedido_emails SET proxima_tentativa_em = now() WHERE pedido_id = ?", id);
        emails.processarPendentes();
        emails.processarPendentes();
        var enviados = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp, times(4)).send(enviados.capture());
        var doPedido = enviados.getAllValues();
        assertThat(doPedido).anySatisfy(mensagem -> {
            assertThat(mensagem.getTo()).containsExactly(comprador + "@teste.local");
            assertThat(mensagem.getSubject()).startsWith("Balaio · Pedido enviado");
            assertThat(mensagem.getText()).contains("ME123456789BR", "Correios", "http://localhost:4321/orders/" + id);
        });
        assertThat(doPedido).anySatisfy(mensagem -> {
            assertThat(mensagem.getTo()).containsExactly(artesao + "@teste.local");
            assertThat(mensagem.getSubject()).startsWith("Balaio · Novo pedido pago");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_emails WHERE pedido_id = ? AND estado = 'enviado'",
                Integer.class, id)).isEqualTo(4);
        var situacao = chamarInterno("GET", "/internal/v1/pedidos/" + id + "/emails", TOKEN_INTERNO);
        assertThat(situacao.statusCode()).isEqualTo(200);
        assertThat(json.readTree(situacao.body()).path("dados").size()).isEqualTo(4);
        assertThat(chamarInterno("GET", "/internal/v1/pedidos/" + id + "/emails", null).statusCode()).isEqualTo(403);
    }

    @Test
    void falhaDefinitivaDeEmailPodeSerReagendadaSemDuplicarOsJaEnviados() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID id = pedidoConfirmado(comprador, artesao, "vaso-email-" + UUID.randomUUID(), "me-1");
        jdbc.update("UPDATE pedido_emails SET estado = 'enviado' WHERE pedido_id <> ?", id);
        jdbc.update("UPDATE pedido_emails SET estado = 'falhou', tentativas = ? WHERE pedido_id = ? AND destinatario = 'comprador'",
                EmailPedidoService.MAXIMO_TENTATIVAS, id);
        jdbc.update("UPDATE pedido_emails SET estado = 'enviado' WHERE pedido_id = ? AND destinatario = 'artesao'", id);
        var reenvio = chamarInterno("POST", "/internal/v1/pedidos/" + id + "/emails/reenviar", TOKEN_INTERNO);
        assertThat(json.readTree(reenvio.body()).path("dados").path("reagendados").asInt()).isEqualTo(1);
        emails.processarPendentes();
        var enviados = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(enviados.capture());
        assertThat(enviados.getAllValues()).singleElement().satisfies(mensagem ->
                        assertThat(mensagem.getTo()).containsExactly(comprador + "@teste.local"));
    }

    @Test
    void freteMelhorEnvioCotaPorCepEItensESnapshotEntraNoTotal() throws Exception {
        UUID artesao = UUID.randomUUID();
        String slug = "jarra-frete-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "disponivel", "atelie", artesao));
        when(origensFrete.cepOrigem(artesao)).thenReturn(Optional.of("56800000"));
        var resposta = chamar("GET", "/api/v1/fretes?cep=01310-100&itens=" + slug + ":2", null, null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        JsonNode opcoes = json.readTree(resposta.body()).path("dados");
        assertThat(opcoes.findValuesAsString("id")).containsExactly("me-1", "me-2");
        assertThat(opcoes.get(0).path("valor").decimalValue()).isEqualByComparingTo("21.50");
        assertThat(opcoes.get(0).path("provedor").asString()).isEqualTo("melhor_envio_sandbox");
        assertThat(opcoes.get(0).path("prazo").asString()).isEqualTo("5 a 7 dias úteis");
        assertThat(AUTORIZACAO_MELHOR_ENVIO.get()).isEqualTo("Bearer token-sandbox-de-teste");
        JsonNode corpo = json.readTree(CORPOS_MELHOR_ENVIO.getLast());
        assertThat(corpo.path("from").path("postal_code").asString()).isEqualTo("56800000");
        assertThat(corpo.path("to").path("postal_code").asString()).isEqualTo("01310100");
        assertThat(corpo.path("products").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(corpo.path("products").get(0).path("insurance_value").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(chamar("GET", "/api/v1/fretes?cep=123", null, null).statusCode()).isEqualTo(400);
        String deOutroAtelie = "cesto-frete-" + UUID.randomUUID();
        when(catalogo.cotar(deOutroAtelie)).thenReturn(new CatalogoCliente.Cotacao(deOutroAtelie, new BigDecimal("40.00"),
                "disponivel", "outro", UUID.randomUUID()));
        var misturado = chamar("GET", "/api/v1/fretes?cep=01310-100&itens=" + slug + ":1," + deOutroAtelie + ":1", null, null);
        assertThat(misturado.statusCode()).isEqualTo(409);
        assertThat(misturado.body()).contains("ATELIES_DIFERENTES").doesNotContain("56800000");
        assertThat(json.readTree(chamar("GET", "/api/v1/fretes", null, null).body()).path("dados").get(0)
                .path("provedor").asString()).isEqualTo("fixo_demonstrativo");

        UUID comprador = UUID.randomUUID();
        UUID pedido = pedidos.checkout(new Checkout(List.of(new Item(slug, 2)), "me-2",
                "checkout_pro", endereco()), comprador, "frete-" + slug);
        var gravado = jdbc.queryForMap("SELECT frete_id, frete, total, frete_cotacao::text AS cotacao FROM pedidos WHERE id = ?", pedido);
        assertThat(gravado.get("frete_id")).isEqualTo("me-2");
        assertThat((BigDecimal) gravado.get("frete")).isEqualByComparingTo("35.10");
        assertThat((BigDecimal) gravado.get("total")).isEqualByComparingTo("195.10");
        JsonNode snapshot = json.readTree((String) gravado.get("cotacao"));
        assertThat(snapshot.path("provedor").asString()).isEqualTo("melhor_envio_sandbox");
        assertThat(snapshot.path("cepDestino").asString()).isEqualTo("52021030");
        assertThat(snapshot.path("origemFrete").asString()).isEqualTo("atelie");
        assertThat(json.readTree(CORPOS_MELHOR_ENVIO.getLast()).path("from").path("postal_code").asString())
                .isEqualTo("56800000");
        assertThat(snapshot.path("transportadora").asString()).isEqualTo("Correios");
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(FreteException.class, () ->
                pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "me-99",
                        "checkout_pro", endereco()), comprador, "frete-inexistente-" + slug)).codigo())
                .isEqualTo("FRETE_INDISPONIVEL");
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(PedidoException.class, () ->
                pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao",
                        "checkout_pro", endereco()), comprador, "frete-fixo-" + slug)).tipo())
                .isEqualTo(PedidoException.Tipo.REQUISICAO_INVALIDA);
    }

    @Test
    void relatorioAgregaNoServidorPorPeriodoIsolandoOArtesao() throws Exception {
        UUID artesao = UUID.randomUUID();
        UUID outroArtesao = UUID.randomUUID();
        String vaso = "vaso-relatorio-" + UUID.randomUUID();
        String jarra = "jarra-relatorio-" + UUID.randomUUID();
        UUID recente = pedidoConfirmado(UUID.randomUUID(), artesao, vaso, "me-1", new BigDecimal("80.00"));
        UUID segundo = pedidoConfirmado(UUID.randomUUID(), artesao, jarra, "me-1");
        UUID antigo = pedidoConfirmado(UUID.randomUUID(), artesao, jarra, "me-1");
        UUID cancelado = pedidoConfirmado(UUID.randomUUID(), artesao, vaso, "me-1", new BigDecimal("80.00"));
        pedidoConfirmado(UUID.randomUUID(), outroArtesao, "alheia-" + UUID.randomUUID(), "me-1");
        jdbc.update("UPDATE pedido_etapas SET criada_em = now() - interval '40 days' WHERE pedido_id = ? AND estado = 'confirmado'", antigo);
        jdbc.update("UPDATE pedidos SET estado = 'reembolso_pendente', encerramento = 'cancelado' WHERE id = ?", cancelado);
        jdbc.update("UPDATE pedidos SET estado = 'producao' WHERE id = ?", segundo);
        String tokenArtesao = token(artesao, "artesao");

        JsonNode trinta = json.readTree(chamar("GET", "/api/v1/artesao/vendas/relatorio?periodo=30d", tokenArtesao, null)
                .body()).path("dados");
        assertThat(trinta.path("quantidadePedidos").asInt()).isEqualTo(2);
        assertThat(trinta.path("receita").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(trinta.path("frete").decimalValue()).isEqualByComparingTo("43.00");
        assertThat(trinta.path("ticketMedio").decimalValue()).isEqualByComparingTo("65.00");
        assertThat(trinta.path("ranking").findValuesAsString("slug")).containsExactly(vaso, jarra);
        assertThat(trinta.path("serie").path("granularidade").asString()).isEqualTo("dia");
        assertThat(trinta.path("serie").path("pontos").size()).isEqualTo(30);
        assertThat(trinta.path("formulaReceita").asString()).contains("frete fica fora");

        JsonNode noventa = json.readTree(chamar("GET", "/api/v1/artesao/vendas/relatorio?periodo=90d", tokenArtesao, null)
                .body()).path("dados");
        assertThat(noventa.path("quantidadePedidos").asInt()).isEqualTo(3);
        assertThat(noventa.path("receita").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(noventa.path("ranking").get(0).path("slug").asString()).isEqualTo(jarra);
        assertThat(noventa.path("ranking").get(0).path("unidades").asInt()).isEqualTo(2);
        assertThat(noventa.path("serie").path("granularidade").asString()).isEqualTo("mes");

        LocalDate hoje = LocalDate.now(PeriodoRelatorio.FUSO);
        JsonNode soHoje = json.readTree(chamar("GET", "/api/v1/artesao/vendas/relatorio?de=" + hoje + "&ate=" + hoje,
                tokenArtesao, null).body()).path("dados");
        assertThat(soHoje.path("periodo").path("preset").asString()).isEqualTo("personalizado");
        assertThat(soHoje.path("quantidadePedidos").asInt()).isEqualTo(2);
        assertThat(chamar("GET", "/api/v1/artesao/vendas/relatorio?de=" + hoje + "&ate=" + hoje.minusDays(1),
                tokenArtesao, null).statusCode()).isEqualTo(400);
        assertThat(chamar("GET", "/api/v1/artesao/vendas/relatorio?periodo=1ano", tokenArtesao, null).statusCode())
                .isEqualTo(400);
        assertThat(chamar("GET", "/api/v1/artesao/vendas/relatorio", null, null).statusCode()).isEqualTo(401);
        assertThat(chamar("GET", "/api/v1/artesao/vendas/relatorio", token(artesao, "comprador"), null).statusCode())
                .isEqualTo(403);
        assertThat(json.readTree(chamar("GET", "/api/v1/artesao/vendas/relatorio?periodo=90d&artesao=" + artesao,
                token(UUID.randomUUID(), "artesao"), null).body()).path("dados").path("quantidadePedidos").asInt())
                .isZero();
        assertThat(recente).isNotNull();
    }

    @Test
    void resumoDoPainelCompoeCommerceECatalogoEExplicitaFalhaParcial() throws Exception {
        UUID artesao = UUID.randomUUID();
        UUID pendente = pedidoConfirmado(UUID.randomUUID(), artesao, "resumo-a-" + UUID.randomUUID(), "me-1");
        UUID emProducao = pedidoConfirmado(UUID.randomUUID(), artesao, "resumo-b-" + UUID.randomUUID(), "me-1");
        jdbc.update("UPDATE pedidos SET estado = 'producao' WHERE id = ?", emProducao);
        PECAS_PAINEL.set("[{\"slug\":\"a\",\"nome\":\"Jarra azul\",\"estadoVenda\":\"disponivel\"},"
                + "{\"slug\":\"b\",\"nome\":\"Vaso reservado\",\"estadoVenda\":\"reservada\"},"
                + "{\"slug\":\"c\",\"nome\":\"Pote vendido\",\"estadoVenda\":\"vendida\"},"
                + "{\"slug\":\"d\",\"nome\":\"Tigela antiga\",\"estadoVenda\":\"inativada\",\"inativadoEm\":\"2026-11-01T00:00:00Z\"},"
                + "{\"slug\":\"e\",\"nome\":\"Rascunho\",\"estadoVenda\":\"rascunho\"}]");
        String tokenArtesao = token(artesao, "artesao");
        chamar("GET", "/api/v1/artesao/painel/resumo", tokenArtesao, null);

        var duracoes = new ArrayList<Long>();
        JsonNode completo = null;
        for (int i = 0; i < 10; i++) {
            long inicio = System.nanoTime();
            var resposta = chamar("GET", "/api/v1/artesao/painel/resumo", tokenArtesao, null);
            duracoes.add((System.nanoTime() - inicio) / 1_000_000);
            assertThat(resposta.statusCode()).isEqualTo(200);
            completo = json.readTree(resposta.body()).path("dados");
        }
        duracoes.sort(Long::compare);
        System.out.println("Latência do resumo do painel (ms, 10 chamadas): " + duracoes);
        assertThat(duracoes.get(8)).isLessThan(500L);
        assertThat(completo.path("versaoContrato").asString()).isEqualTo("painel-resumo.v1");
        assertThat(completo.path("parcial").asBoolean()).isFalse();
        assertThat(completo.path("fontesIndisponiveis").size()).isZero();
        assertThat(completo.path("pedidosAAceitar").asInt()).isEqualTo(1);
        assertThat(completo.path("emProducao").asInt()).isEqualTo(1);
        assertThat(completo.path("faturamentoMes").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(completo.path("pecasAtivas").asInt()).isEqualTo(2);
        assertThat(completo.path("avisos").findValuesAsString("motivo"))
                .containsExactly("Peça única vendida", "Peça inativada");

        MODO_CATALOGO.set("falha");
        JsonNode semCatalogo = json.readTree(chamar("GET", "/api/v1/artesao/painel/resumo", tokenArtesao, null).body())
                .path("dados");
        assertThat(semCatalogo.path("parcial").asBoolean()).isTrue();
        assertThat(semCatalogo.path("fontesIndisponiveis").get(0).asString()).isEqualTo("catalogo");
        assertThat(semCatalogo.path("pecasAtivas").isNull()).isTrue();
        assertThat(semCatalogo.path("avisos").isNull()).isTrue();
        assertThat(semCatalogo.path("faturamentoMes").decimalValue()).isEqualByComparingTo("100.00");

        MODO_CATALOGO.set("lento");
        long inicio = System.nanoTime();
        JsonNode lento = json.readTree(chamar("GET", "/api/v1/artesao/painel/resumo", tokenArtesao, null).body())
                .path("dados");
        long duracaoLento = (System.nanoTime() - inicio) / 1_000_000;
        System.out.println("Resumo com catálogo lento (ms): " + duracaoLento);
        assertThat(lento.path("parcial").asBoolean()).isTrue();
        assertThat(duracaoLento).isLessThan(1500L);
        assertThat(chamar("GET", "/api/v1/artesao/painel/resumo", token(artesao, "comprador"), null).statusCode())
                .isEqualTo(403);
        assertThat(pendente).isNotNull();
    }

    private UUID pedidoConfirmado(UUID comprador, UUID artesao, String slug, String freteId) {
        return pedidoConfirmado(comprador, artesao, slug, freteId, new BigDecimal("50.00"));
    }

    private UUID pedidoConfirmado(UUID comprador, UUID artesao, String slug, String freteId, BigDecimal preco) {
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, preco,
                "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), freteId,
                "checkout_pro", endereco()), comprador, slug + "-" + UUID.randomUUID());
        pedidos.processar(id);
        pagamentos.preparar(id);
        String pagamentoId = "pagamento-" + id;
        BigDecimal total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(pagamentoId, id.toString(),
                total, "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem("pref-" + id, id.toString()));
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("confirmado");
        return id;
    }

    private Map<String, String> endereco() {
        return Map.of("cep", "52021-030", "endereco", "Rua de teste, 10", "cidade", "Recife", "estado", "PE");
    }

    private JsonNode ultimaEtapa(JsonNode pedido) {
        return pedido.path("etapas").get(pedido.path("etapas").size() - 1);
    }

    private HttpResponse<String> chamar(String metodo, String caminho, String token, String corpo) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("PATCH".equals(metodo)) request.method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> chamarInterno(String metodo, String caminho, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho));
        if (token != null) request.header("X-Balaio-Service-Token", token);
        if ("POST".equals(metodo)) request.POST(HttpRequest.BodyPublishers.noBody());
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer servidorExterno() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v2/me/shipment/calculate", troca -> {
                synchronized (CORPOS_MELHOR_ENVIO) {
                    CORPOS_MELHOR_ENVIO.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                AUTORIZACAO_MELHOR_ENVIO.set(troca.getRequestHeaders().getFirst("Authorization"));
                responder(troca, 200, "[{\"id\":2,\"name\":\"SEDEX\",\"price\":\"35.10\",\"custom_price\":\"35.10\","
                        + "\"delivery_time\":2,\"delivery_range\":{\"min\":1,\"max\":2},\"company\":{\"id\":1,\"name\":\"Correios\"}},"
                        + "{\"id\":3,\"name\":\".Package\",\"error\":\"Transportadora não atende este trecho.\",\"company\":{\"name\":\"Jadlog\"}},"
                        + "{\"id\":1,\"name\":\"PAC\",\"price\":\"21.50\",\"delivery_time\":7,"
                        + "\"delivery_range\":{\"min\":5,\"max\":7},\"company\":{\"id\":1,\"name\":\"Correios\"}}]");
            });
            servidor.createContext("/api/v1/artesaos/", troca -> {
                switch (MODO_CATALOGO.get()) {
                    case "falha" -> responder(troca, 500, "{\"dados\":null,\"erro\":{\"codigo\":\"ERRO\"}}");
                    case "lento" -> {
                        try { Thread.sleep(2000); } catch (InterruptedException interrompido) { Thread.currentThread().interrupt(); }
                        responder(troca, 200, "{\"dados\":[],\"erro\":null}");
                    }
                    default -> responder(troca, 200, "{\"dados\":" + PECAS_PAINEL.get() + ",\"erro\":null}");
                }
            });
            servidor.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            servidor.start();
            return servidor;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void responder(HttpExchange troca, int status, String corpo) {
        try (troca) {
            byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "application/json");
            troca.sendResponseHeaders(status, bytes.length);
            troca.getResponseBody().write(bytes);
        } catch (Exception ignorada) {
            troca.close();
        }
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
            Path path = Files.createTempFile("commerce-pos-venda-public-", ".pem");
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
