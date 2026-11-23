package br.com.balaio.commerce.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.FreteService;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;
import br.com.balaio.commerce.shipping.integration.MelhorEnvioSandboxClient;
import br.com.balaio.commerce.shipping.integration.ProvedorFreteConfig;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MelhorEnvioSandboxClientTest {
    private static final MelhorEnvioSandboxClient.Pacote PACOTE =
            new MelhorEnvioSandboxClient.Pacote(15, 20, 25, new BigDecimal("1.0"));
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> resposta = new AtomicReference<>("[]");
    private final AtomicReference<String> corpoRecebido = new AtomicReference<>();
    private final AtomicReference<String> autorizacao = new AtomicReference<>();
    private final AtomicReference<String> agente = new AtomicReference<>();
    private HttpServer servidor;

    @BeforeEach
    void iniciar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/v2/me/shipment/calculate", troca -> {
            corpoRecebido.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacao.set(troca.getRequestHeaders().getFirst("Authorization"));
            agente.set(troca.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = resposta.get().getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "application/json");
            troca.sendResponseHeaders(status.get(), bytes.length);
            troca.getResponseBody().write(bytes);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    @Test
    void enviaCepItensDimensoesETokenEDevolveOpcoesValidasOrdenadasPorPreco() throws Exception {
        resposta.set("[{\"id\":2,\"name\":\"SEDEX\",\"price\":\"40.00\",\"custom_price\":\"38.25\","
                + "\"custom_delivery_time\":3,\"custom_delivery_range\":{\"min\":2,\"max\":3},\"company\":{\"name\":\"Correios\"}},"
                + "{\"id\":17,\"name\":\"Mini Envios\",\"error\":\"Serviço indisponível para o trecho.\"},"
                + "{\"id\":1,\"name\":\"PAC\",\"price\":\"19.9\",\"delivery_time\":8,\"company\":{\"name\":\"Correios\"}}]");
        var cliente = cliente("1,2,17");

        List<OpcaoFrete> opcoes = cliente.cotar("01310100", List.of(new VolumeFrete("jarra", 2, new BigDecimal("75.00")),
                new VolumeFrete("vaso", 1, new BigDecimal("30.00"))));

        assertThat(opcoes).extracting(OpcaoFrete::id).containsExactly("me-1", "me-2");
        assertThat(opcoes.get(0).valor()).isEqualByComparingTo("19.90");
        assertThat(opcoes.get(0).prazo()).isEqualTo("8 dias úteis");
        assertThat(opcoes.get(1).valor()).isEqualByComparingTo("38.25");
        assertThat(opcoes.get(1).prazo()).isEqualTo("2 a 3 dias úteis");
        assertThat(opcoes.get(1).nome()).isEqualTo("Correios SEDEX");
        assertThat(opcoes).allSatisfy(opcao -> assertThat(opcao.provedor()).isEqualTo("melhor_envio_sandbox"));
        assertThat(autorizacao.get()).isEqualTo("Bearer token-sandbox");
        assertThat(agente.get()).isEqualTo("Balaio teste (equipe@teste.local)");
        JsonNode corpo = JsonMapper.builder().build().readTree(corpoRecebido.get());
        assertThat(corpo.path("from").path("postal_code").asString()).isEqualTo("50030230");
        assertThat(corpo.path("to").path("postal_code").asString()).isEqualTo("01310100");
        assertThat(corpo.path("services").asString()).isEqualTo("1,2,17");
        assertThat(corpo.path("products").size()).isEqualTo(2);
        JsonNode primeiro = corpo.path("products").get(0);
        assertThat(primeiro.path("id").asString()).isEqualTo("jarra");
        assertThat(primeiro.path("quantity").asInt()).isEqualTo(2);
        assertThat(primeiro.path("width").asInt()).isEqualTo(20);
        assertThat(primeiro.path("height").asInt()).isEqualTo(15);
        assertThat(primeiro.path("length").asInt()).isEqualTo(25);
        assertThat(primeiro.path("weight").decimalValue()).isEqualByComparingTo("1.0");
        assertThat(primeiro.path("insurance_value").decimalValue()).isEqualByComparingTo("75.00");
    }

    @Test
    void tokenRecusadoOuRespostaInvalidaViramFreteIndisponivelSemValorInventado() {
        var cliente = cliente("");
        status.set(401);
        resposta.set("{\"message\":\"Unauthenticated.\"}");
        assertThatThrownBy(() -> cliente.cotar("01310100", List.of(new VolumeFrete("jarra", 1, BigDecimal.TEN))))
                .isInstanceOfSatisfying(FreteException.class, erro -> {
                    assertThat(erro.tipo()).isEqualTo(FreteException.Tipo.COTACAO_INDISPONIVEL);
                    assertThat(erro.codigo()).isEqualTo("FRETE_INDISPONIVEL");
                });
        status.set(200);
        resposta.set("{\"inesperado\":true}");
        assertThatThrownBy(() -> cliente.cotar("01310100", List.of(new VolumeFrete("jarra", 1, BigDecimal.TEN))))
                .isInstanceOf(FreteException.class);
        resposta.set("[{\"id\":1,\"name\":\"PAC\",\"error\":\"CEP de destino inválido\"}]");
        assertThat(cliente.cotar("01310100", List.of(new VolumeFrete("jarra", 1, BigDecimal.TEN)))).isEmpty();
    }

    @Test
    void recusaAmbienteDeProducaoECepDeOrigemInvalido() {
        assertThatThrownBy(() -> new MelhorEnvioSandboxClient(RestClient.builder(), "https://melhorenvio.com.br", "token", "50030230",
                "Balaio", "", PACOTE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MelhorEnvioSandboxClient(RestClient.builder(), "https://sandbox.melhorenvio.com.br", "token", "123",
                "Balaio", "", PACOTE)).isInstanceOf(IllegalStateException.class);
        assertThat(new MelhorEnvioSandboxClient(RestClient.builder(), "https://sandbox.melhorenvio.com.br", "token", "50030-230",
                "Balaio", "", PACOTE).reconheceOpcao("me-2")).isTrue();
    }

    @Test
    void semTokenUsaCotacaoFixaIdentificadaEEscolhaGeraSnapshot() {
        var service = new FreteService(new ProvedorFreteConfig().provedorFrete(RestClient.builder(),
                "https://sandbox.melhorenvio.com.br", "", "50030230", "Balaio", "", 15, 20, 25, BigDecimal.ONE),
                slug -> BigDecimal.TEN);
        assertThat(service.provedor()).isEqualTo("fixo_demonstrativo");
        assertThat(service.reconheceOpcao("padrao")).isTrue();
        assertThat(service.reconheceOpcao("me-1")).isFalse();
        var escolha = service.escolher("padrao", "52021-030", List.of());
        assertThat(escolha.opcao().valor()).isEqualByComparingTo("38.90");
        assertThat(escolha.snapshot()).containsEntry("provedor", "fixo_demonstrativo").containsEntry("cepDestino", "52021030")
                .containsKey("cotadoEm");
        assertThatThrownBy(() -> service.cotar("00000-000", List.of()))
                .isInstanceOfSatisfying(FreteException.class, erro -> assertThat(erro.codigo()).isEqualTo("CEP_INVALIDO"));
        assertThatThrownBy(() -> service.escolher("padrao", "5202103", List.of()))
                .isInstanceOfSatisfying(FreteException.class,
                        erro -> assertThat(erro.tipo()).isEqualTo(FreteException.Tipo.CEP_INVALIDO));
        var comToken = new FreteService(new ProvedorFreteConfig().provedorFrete(RestClient.builder(),
                "http://127.0.0.1:" + servidor.getAddress().getPort(), "token", "50030230",
                "Balaio", "", 15, 20, 25, BigDecimal.ONE), slug -> BigDecimal.TEN);
        assertThat(comToken.provedor()).isEqualTo("melhor_envio_sandbox");
        resposta.set("[{\"id\":1,\"name\":\"PAC\",\"price\":\"20.00\",\"delivery_time\":5}]");
        assertThatThrownBy(() -> comToken.escolher("me-2", "01310100", List.of()))
                .isInstanceOfSatisfying(FreteException.class, erro -> {
                    assertThat(erro.tipo()).isEqualTo(FreteException.Tipo.OPCAO_INDISPONIVEL);
                    assertThat(erro.codigo()).isEqualTo("FRETE_INDISPONIVEL");
                });
    }

    private MelhorEnvioSandboxClient cliente(String servicos) {
        return new MelhorEnvioSandboxClient(RestClient.builder(), "http://127.0.0.1:" + servidor.getAddress().getPort(), "token-sandbox",
                "50030-230", "Balaio teste (equipe@teste.local)", servicos, PACOTE);
    }
}
