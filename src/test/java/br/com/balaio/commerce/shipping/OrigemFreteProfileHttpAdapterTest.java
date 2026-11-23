package br.com.balaio.commerce.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.shipping.integration.OrigemFreteProfileHttpAdapter;

class OrigemFreteProfileHttpAdapterTest {
    private static final String TOKEN = "12345678901234567890123456789012";

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> resposta = new AtomicReference<>("{}");
    private final AtomicReference<String> caminho = new AtomicReference<>();
    private final AtomicReference<String> tokenRecebido = new AtomicReference<>();
    private final AtomicInteger chamadas = new AtomicInteger();
    private HttpServer servidor;

    @BeforeEach
    void iniciar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            chamadas.incrementAndGet();
            caminho.set(troca.getRequestURI().getPath());
            tokenRecebido.set(troca.getRequestHeaders().getFirst("X-Balaio-Service-Token"));
            byte[] corpo = resposta.get().getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "application/json");
            troca.sendResponseHeaders(status.get(), corpo.length);
            try (var saida = troca.getResponseBody()) { saida.write(corpo); }
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    @Test
    void consultaRotaInternaComTokenDeServicoEDevolveCepNormalizado() {
        UUID artesao = UUID.randomUUID();
        resposta.set("{\"dados\":{\"cepOrigem\":\"56800000\",\"prazoPadraoDias\":7,\"encomendasPausadas\":false},\"erro\":null}");

        assertThat(adaptador(TOKEN).cepOrigem(artesao)).hasValue("56800000");
        assertThat(caminho.get()).isEqualTo("/internal/v1/contas/" + artesao + "/origem-frete");
        assertThat(tokenRecebido.get()).isEqualTo(TOKEN);
    }

    @Test
    void cepAusenteOuInvalidoDevolveVazio() {
        resposta.set("{\"dados\":{\"cepOrigem\":null,\"prazoPadraoDias\":1,\"encomendasPausadas\":false},\"erro\":null}");
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        resposta.set("{\"dados\":{\"cepOrigem\":\"00000000\"},\"erro\":null}");
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        resposta.set("{\"inesperado\":true}");
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
    }

    @Test
    void falhasDoProfileViramFallbackSemPropagarErro() {
        status.set(404);
        resposta.set("{\"dados\":null,\"erro\":{\"codigo\":\"PERFIL_NAO_ENCONTRADO\"}}");
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        status.set(403);
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        status.set(500);
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        status.set(200);
        resposta.set("não é json");
        assertThat(adaptador(TOKEN).cepOrigem(UUID.randomUUID())).isEmpty();
        assertThat(new OrigemFreteProfileHttpAdapter(RestClient.builder(), "http://127.0.0.1:1", TOKEN)
                .cepOrigem(UUID.randomUUID())).isEmpty();
    }

    @Test
    void semTokenInternoOuSemArtesaoNaoChamaProfile() {
        assertThat(adaptador("curto").cepOrigem(UUID.randomUUID())).isEmpty();
        assertThat(adaptador(TOKEN).cepOrigem(null)).isEmpty();
        assertThat(chamadas.get()).isZero();
    }

    private OrigemFreteProfileHttpAdapter adaptador(String token) {
        return new OrigemFreteProfileHttpAdapter(RestClient.builder(),
                "http://127.0.0.1:" + servidor.getAddress().getPort(), token);
    }
}
