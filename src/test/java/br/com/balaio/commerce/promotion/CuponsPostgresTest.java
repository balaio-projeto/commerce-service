package br.com.balaio.commerce.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.balaio.commerce.integration.catalogo.CatalogoCliente;
import br.com.balaio.commerce.order.application.PedidoParaPagamentoService;
import br.com.balaio.commerce.order.application.PedidoService;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.order.application.port.out.EnderecoSalvo;
import br.com.balaio.commerce.payment.application.CheckoutProService;
import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;
import br.com.balaio.commerce.promotion.application.CupomRecusado;
import br.com.balaio.commerce.promotion.application.port.in.AdministrarCuponsUseCase;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.promotion.domain.RecusaCupom;
import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.port.out.OrigemFreteAtelie;
import br.com.balaio.commerce.shipping.domain.OrigemAtelie;
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
        "payment.mercadopago.frontend-return-url=https://test.example"})
@Import(CuponsPostgresTest.Postgres.class)
class CuponsPostgresTest {
    private static final String ISSUER = "https://profile.example.test";
    private static final String AUDIENCE = "balaio-api-test";
    private static final KeyPair KEYS = chaves();
    private static final Path PUBLIC_KEY = chavePublica();
    private static final Map<String, String> ENDERECO = Map.of("cep", "52021030", "endereco", "Rua de teste, 10",
            "cidade", "Recife", "estado", "PE");

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

    @Value("${local.server.port}") int port;
    @Autowired PedidoService pedidos;
    @Autowired CheckoutProService pagamentos;
    @Autowired AdministrarCuponsUseCase cupons;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean PagamentoGateway provedor;
    @MockitoBean EnderecoSalvo enderecos;
    @MockitoBean OrigemFreteAtelie origens;
    private final HttpClient http = HttpClient.newHttpClient();
    private final UUID artesao = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        when(origens.origem(any())).thenReturn(Optional.empty());
    }

    @Test
    void adminGerenciaCuponsPorHttpEOutrosPapeisSaoBarrados() throws Exception {
        String tokenAdmin = token(admin, "admin");
        String codigo = codigo("HTTP");
        String corpo = """
                {"codigo":"%s","descricao":"Dez por cento","tipo":"percentual","valor":10,"pedidoMinimo":50,
                 "limiteUsos":100,"limitePorComprador":1,"fim":"2099-12-31T23:59:00-03:00"}
                """.formatted(codigo.toLowerCase());

        assertThat(chamar("POST", "/api/v1/admin/cupons", null, corpo).statusCode()).isEqualTo(401);
        assertThat(chamar("POST", "/api/v1/admin/cupons", token(UUID.randomUUID(), "comprador"), corpo).statusCode())
                .isEqualTo(403);
        var criado = chamar("POST", "/api/v1/admin/cupons", tokenAdmin, corpo);
        assertThat(criado.statusCode()).isEqualTo(201);
        JsonNode dados = json.readTree(criado.body()).path("dados");
        String id = dados.path("id").asString();
        assertThat(dados.path("codigo").asString()).isEqualTo(codigo);
        assertThat(dados.path("situacao").asString()).isEqualTo("ativo");
        assertThat(dados.path("custeadoPor").asString()).isEqualTo("plataforma");

        var invalido = chamar("POST", "/api/v1/admin/cupons", tokenAdmin,
                "{\"codigo\":\"" + codigo + "\",\"descricao\":\"x\",\"tipo\":\"percentual\",\"valor\":150}");
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalido.body()).path("erro").path("campos").propertyNames())
                .contains("codigo", "descricao", "valor");

        var editado = chamar("PUT", "/api/v1/admin/cupons/" + id, tokenAdmin, corpo.replace("\"valor\":10", "\"valor\":15"));
        assertThat(json.readTree(editado.body()).path("dados").path("valor").decimalValue()).isEqualByComparingTo("15");
        var desativado = chamar("PATCH", "/api/v1/admin/cupons/" + id + "/situacao", tokenAdmin, "{\"ativo\":false}");
        assertThat(json.readTree(desativado.body()).path("dados").path("situacao").asString()).isEqualTo("inativo");
        var lista = json.readTree(chamar("GET", "/api/v1/admin/cupons", tokenAdmin, null).body()).path("dados");
        assertThat(lista.toString()).contains(id);
        assertThat(chamar("GET", "/api/v1/admin/cupons/" + UUID.randomUUID(), tokenAdmin, null).statusCode()).isEqualTo(404);
    }

    @Test
    void previaDoCheckoutValidaNoServidorEExplicaARecusa() throws Exception {
        String slug = peca("80.00");
        String valido = criar(new DadosCupom(codigo("PREVIA"), "Vinte reais", "valor_fixo", new BigDecimal("20"), null,
                new BigDecimal("50"), null, null, null, null, true));
        String minimo = criar(new DadosCupom(codigo("MINIMO"), "Mínimo alto", "valor_fixo", BigDecimal.TEN, null,
                new BigDecimal("500"), null, null, null, null, true));
        String expirado = criar(new DadosCupom(codigo("VENCIDO"), "Vencido", "valor_fixo", BigDecimal.TEN, null, null,
                OffsetDateTime.now().minusDays(2), OffsetDateTime.now().minusDays(1), null, null, true));
        String comprador = token(UUID.randomUUID(), "comprador");
        String itens = "[{\"slug\":\"" + slug + "\",\"quantidade\":1}]";

        var ok = chamar("POST", "/api/v1/checkout/cupom", comprador,
                "{\"itens\":" + itens + ",\"cupom\":\"" + valido.toLowerCase() + "\",\"frete\":38.90}");
        JsonNode previa = json.readTree(ok.body()).path("dados");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(previa.path("codigo").asString()).isEqualTo(valido);
        assertThat(previa.path("descontoProdutos").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(previa.path("subtotalProdutos").decimalValue()).isEqualByComparingTo("80.00");

        assertRecusaHttp(comprador, itens, minimo, "CUPOM_PEDIDO_MINIMO", "a partir de R$ 500,00");
        assertRecusaHttp(comprador, itens, expirado, "CUPOM_EXPIRADO", "expirou");
        assertRecusaHttp(comprador, itens, "NAOEXISTE", "CUPOM_INVALIDO", "Confira o código");
        assertThat(chamar("POST", "/api/v1/checkout/cupom", null, "{\"itens\":" + itens + "}").statusCode()).isEqualTo(401);
    }

    @Test
    void checkoutComCupomGravaSnapshotEEnviaAoProvedorOTotalComDesconto() throws Exception {
        String slug = peca("100.00");
        String codigo = criar(new DadosCupom(codigo("DEZ"), "Dez por cento", "percentual", new BigDecimal("10"), null,
                null, null, null, null, null, true));
        UUID comprador = UUID.randomUUID();

        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro", ENDERECO, null,
                codigo.toLowerCase()), comprador, "chave-" + UUID.randomUUID());
        pedidos.processar(id);
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenReturn(new PagamentoGateway.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id));
        pagamentos.preparar(id);

        BigDecimal total = pedidos.consultarInterno(id).total();
        assertThat(total).isEqualByComparingTo("128.90");
        assertThat(totalEnviadoAoProvedor(id)).isEqualByComparingTo(total);
        var gravado = jdbc.queryForMap("SELECT cupom_codigo, desconto_produtos, desconto_frete, frete, "
                + "cupom_snapshot->>'custeadoPor' AS custeio, cupom_snapshot->>'valor' AS valor FROM pedidos WHERE id = ?", id);
        assertThat(gravado.get("cupom_codigo")).isEqualTo(codigo);
        assertThat((BigDecimal) gravado.get("desconto_produtos")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) gravado.get("frete")).isEqualByComparingTo("38.90");
        assertThat(gravado.get("custeio")).isEqualTo("plataforma");
        assertThat(usos(codigo)).isEqualTo(1);
        var itensDoPedido = jdbc.queryForObject("SELECT sum(preco * quantidade) FROM pedido_itens WHERE pedido_id = ?",
                BigDecimal.class, id);
        assertThat(itensDoPedido).isEqualByComparingTo("100.00");

        var detalhe = json.readTree(chamar("GET", "/api/v1/pedidos/" + id, token(comprador, "comprador"), null).body())
                .path("dados");
        assertThat(detalhe.path("desconto").path("cupom").asString()).isEqualTo(codigo);
        assertThat(detalhe.path("desconto").path("produtos").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(detalhe.path("desconto").path("valorSemDesconto").decimalValue()).isEqualByComparingTo("138.90");
        assertThat(detalhe.path("desconto").path("custeadoPor").asString()).isEqualTo("plataforma");

        String pagamentoId = "pagamento-" + id;
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(pagamentoId, id.toString(),
                new BigDecimal("128.90"), "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem("pref-" + id, id.toString()));
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("confirmado");
    }

    @Test
    void freteGratisERetiradaNoAtelie() {
        String slug = peca("60.00");
        String freteGratis = criar(new DadosCupom(codigo("FRETE"), "Frete grátis", "frete_gratis", null, null, null,
                null, null, null, null, true));
        when(origens.origem(artesao)).thenReturn(Optional.of(new OrigemAtelie("52021000", true, true, "Graças", "Recife")));
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(chamada -> new PagamentoGateway.Preferencia(
                "pref-" + chamada.getArgument(0), "https://sandbox.mercadopago.com/checkout/pay?pref_id=x"));

        UUID comFreteGratis = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                ENDERECO, null, freteGratis), UUID.randomUUID(), "chave-" + UUID.randomUUID());
        pedidos.processar(comFreteGratis);
        pagamentos.preparar(comFreteGratis);
        assertThat(pedidos.consultarInterno(comFreteGratis).total()).isEqualByComparingTo("60.00");
        assertThat(totalEnviadoAoProvedor(comFreteGratis)).isEqualByComparingTo("60.00");

        UUID retirada = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "retirada-atelie", "checkout_pro",
                ENDERECO), UUID.randomUUID(), "chave-" + UUID.randomUUID());
        var resumo = pedidos.consultarInterno(retirada);
        assertThat(resumo.total()).isEqualByComparingTo("60.00");
        assertThat(resumo.entrega().frete()).isEqualByComparingTo("0.00");
        assertThat(resumo.entrega().freteProvedor()).isEqualTo("balaio_local");
        assertThat(jdbc.queryForObject("SELECT frete_cotacao->>'detalhe' FROM pedidos WHERE id = ?", String.class, retirada))
                .contains("Graças, Recife").doesNotContain("52021000");

        UUID local = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "entrega-local", "checkout_pro",
                ENDERECO), UUID.randomUUID(), "chave-" + UUID.randomUUID());
        assertThat(pedidos.consultarInterno(local).entrega().freteOpcao()).isEqualTo("Entrega local do ateliê");

        assertThatThrownBy(() -> pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "retirada-atelie",
                "checkout_pro", ENDERECO, null, freteGratis), UUID.randomUUID(), "chave-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(CupomRecusado.class,
                        erro -> assertThat(erro.motivo()).isEqualTo(RecusaCupom.SEM_DESCONTO));
        when(origens.origem(artesao)).thenReturn(Optional.of(new OrigemAtelie("52021000", false, false, null, null)));
        assertThatThrownBy(() -> pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "retirada-atelie",
                "checkout_pro", ENDERECO), UUID.randomUUID(), "chave-" + UUID.randomUUID()))
                .isInstanceOf(FreteException.class);
    }

    @Test
    void usosConcorrentesRespeitamLimiteTotalEPorComprador() throws Exception {
        String slug = peca("40.00");
        String total = criar(new DadosCupom(codigo("TRES"), "Só três", "valor_fixo", new BigDecimal("5"), null, null,
                null, null, 3, null, true));
        var resultados = concorrer(8, indice -> pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao",
                "checkout_pro", ENDERECO, null, total), UUID.randomUUID(), "chave-" + UUID.randomUUID()));
        assertThat(resultados.stream().filter(UUID.class::isInstance)).hasSize(3);
        assertThat(resultados.stream().filter(CupomRecusado.class::isInstance).map(CupomRecusado.class::cast)
                .map(CupomRecusado::motivo)).hasSize(5).containsOnly(RecusaCupom.ESGOTADO);
        assertThat(usos(total)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cupom_usos u JOIN cupons c ON c.id = u.cupom_id "
                + "WHERE c.codigo = ?", Integer.class, total)).isEqualTo(3);

        String porComprador = criar(new DadosCupom(codigo("UMA"), "Uma por pessoa", "valor_fixo", new BigDecimal("5"),
                null, null, null, null, null, 1, true));
        UUID mesmoComprador = UUID.randomUUID();
        var doMesmo = concorrer(4, indice -> pedidos.checkout(new Checkout(List.of(new Item(slug, 1)), "padrao",
                "checkout_pro", ENDERECO, null, porComprador), mesmoComprador, "chave-" + indice));
        assertThat(doMesmo.stream().filter(UUID.class::isInstance)).hasSize(1);
        assertThat(usos(porComprador)).isEqualTo(1);
    }

    @Test
    void pedidoCanceladoDevolveOUsoERepeticaoIdempotenteNaoConsomeDeNovo() {
        String slug = peca("70.00");
        String codigo = criar(new DadosCupom(codigo("UNICO"), "Uso único", "valor_fixo", new BigDecimal("7"), null, null,
                null, null, 1, null, true));
        UUID comprador = UUID.randomUUID();
        var comando = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro", ENDERECO, null, codigo);

        UUID id = pedidos.checkout(comando, comprador, "chave-unica");
        assertThat(pedidos.checkout(comando, comprador, "chave-unica")).isEqualTo(id);
        assertThat(usos(codigo)).isEqualTo(1);
        assertThatThrownBy(() -> pedidos.checkout(comando, UUID.randomUUID(), "outra"))
                .isInstanceOfSatisfying(CupomRecusado.class, erro -> assertThat(erro.motivo()).isEqualTo(RecusaCupom.ESGOTADO));

        pedidos.encerrar(id, comprador, "cancelado", "Desisti da compra.");
        pedidos.processar(id);

        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        assertThat(usos(codigo)).isZero();
        assertThat(jdbc.queryForObject("SELECT estado FROM cupom_usos WHERE pedido_id = ?", String.class, id))
                .isEqualTo("liberado");
        assertThat(pedidos.checkout(comando, UUID.randomUUID(), "depois")).isNotNull();
    }

    private void assertRecusaHttp(String token, String itens, String codigo, String erro, String trecho) throws Exception {
        var resposta = chamar("POST", "/api/v1/checkout/cupom", token,
                "{\"itens\":" + itens + ",\"cupom\":\"" + codigo + "\"}");
        assertThat(resposta.statusCode()).isEqualTo(422);
        JsonNode corpo = json.readTree(resposta.body()).path("erro");
        assertThat(corpo.path("codigo").asString()).isEqualTo(erro);
        assertThat(corpo.path("mensagem").asString()).contains(trecho);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal totalEnviadoAoProvedor(UUID pedido) {
        ArgumentCaptor<List<PagamentoGateway.Item>> itens = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<BigDecimal> frete = ArgumentCaptor.forClass(BigDecimal.class);
        verify(provedor).criar(org.mockito.ArgumentMatchers.eq(pedido), itens.capture(), frete.capture());
        assertThat(itens.getValue()).allSatisfy(item -> assertThat(item.preco().signum()).isPositive());
        if (itens.getValue().size() == 1 && PedidoParaPagamentoService.LINHA_PECAS_COM_CUPOM.equals(itens.getValue().getFirst().slug()))
            assertThat(itens.getValue().getFirst().quantidade()).isEqualTo(1);
        return itens.getValue().stream().map(item -> item.preco().multiply(BigDecimal.valueOf(item.quantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(frete.getValue());
    }

    private List<Object> concorrer(int quantidade, Tarefa tarefa) throws Exception {
        var largada = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(quantidade)) {
            var futuros = new ArrayList<Future<Object>>();
            for (int i = 0; i < quantidade; i++) {
                int indice = i;
                Callable<Object> chamada = () -> {
                    largada.await();
                    try {
                        return tarefa.executar(indice);
                    } catch (RuntimeException falha) {
                        return falha;
                    }
                };
                futuros.add(executor.submit(chamada));
            }
            largada.countDown();
            var resultados = new ArrayList<Object>();
            for (var futuro : futuros) resultados.add(futuro.get());
            return resultados;
        }
    }

    private interface Tarefa {
        Object executar(int indice);
    }

    private String peca(String preco) {
        String slug = "peca-cupom-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal(preco), "encomenda",
                "atelie", artesao));
        return slug;
    }

    private String criar(DadosCupom dados) {
        return cupons.criar(dados, admin).cupom().codigo();
    }

    private int usos(String codigo) {
        return jdbc.queryForObject("SELECT usos FROM cupons WHERE codigo = ?", Integer.class, codigo);
    }

    private static String codigo(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private HttpResponse<String> chamar(String metodo, String caminho, String token, String corpo) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(UUID subject, String papel) throws Exception {
        long agora = Instant.now().getEpochSecond();
        var claims = Map.<String, Object>of("sub", subject.toString(), "papel", papel, "iss", ISSUER,
                "aud", List.of(AUDIENCE), "iat", agora - 5, "exp", agora + 3600, "jti", UUID.randomUUID().toString());
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
            Path path = Files.createTempFile("commerce-cupons-public-", ".pem");
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
