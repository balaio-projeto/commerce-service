package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.order.application.port.out.PerfisCompradores.PerfilComprador;
import br.com.balaio.commerce.order.integration.PerfisCompradoresHttpAdapter;

class PerfisCompradoresHttpAdapterTest {
    private static final String TOKEN = "12345678901234567890123456789012";

    @Test
    void consultaNomeUmaVezPorCompradorEFotosEmLoteSemExporEmail() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID bruno = UUID.randomUUID();
        var contatosConsultados = new CopyOnWriteArrayList<String>();
        var corposImagens = new CopyOnWriteArrayList<String>();
        var tokens = ConcurrentHashMap.<String>newKeySet();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/v1/contas/", troca -> {
            tokens.add(troca.getRequestHeaders().getFirst("X-Balaio-Service-Token"));
            String caminho = troca.getRequestURI().getPath();
            if (caminho.equals("/internal/v1/contas/imagens")) {
                corposImagens.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responder(troca, 200, "{\"dados\":{\"" + ana + "\":\"/api/v1/arquivos/perfis/ana.webp\"},\"erro\":null}");
                return;
            }
            String id = caminho.replace("/internal/v1/contas/", "").replace("/contato", "");
            contatosConsultados.add(id);
            String nome = id.equals(ana.toString()) ? "Ana Souza" : "Bruno Lima";
            responder(troca, 200, "{\"dados\":{\"id\":\"" + id + "\",\"nome\":\"" + nome
                    + "\",\"email\":\"segredo@teste.local\"},\"erro\":null}");
        });
        servidor.start();
        try {
            var perfis = adaptador(servidor).consultar(List.of(ana, bruno, ana));
            assertThat(perfis).containsExactlyInAnyOrderEntriesOf(Map.of(
                    ana, new PerfilComprador("Ana Souza", "/api/v1/arquivos/perfis/ana.webp"),
                    bruno, new PerfilComprador("Bruno Lima", null)));
            assertThat(contatosConsultados).containsExactlyInAnyOrder(ana.toString(), bruno.toString());
            assertThat(corposImagens).hasSize(1);
            assertThat(corposImagens.getFirst()).contains(ana.toString(), bruno.toString());
            assertThat(tokens).containsExactly(TOKEN);
            assertThat(perfis.values().toString()).doesNotContain("segredo@teste.local");
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void perfilForaDoArDevolveNomeEFotoNulosSemRepetirTentativasDeNome() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID bruno = UUID.randomUUID();
        var chamadas = new AtomicInteger();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/v1/contas/", troca -> {
            chamadas.incrementAndGet();
            responder(troca, 503, "{\"dados\":null,\"erro\":{\"codigo\":\"INDISPONIVEL\"}}");
        });
        servidor.start();
        try {
            var perfis = adaptador(servidor).consultar(List.of(ana, bruno));
            assertThat(perfis).containsExactlyInAnyOrderEntriesOf(Map.of(
                    ana, PerfilComprador.DESCONHECIDO, bruno, PerfilComprador.DESCONHECIDO));
            assertThat(chamadas.get()).isEqualTo(2);
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void semTokenInternoNaoConsultaOPerfil() {
        var adaptador = new PerfisCompradoresHttpAdapter(RestClient.builder(), "http://127.0.0.1:9", "curto");
        assertThat(adaptador.consultar(List.of(UUID.randomUUID()))).isEmpty();
    }

    private static PerfisCompradoresHttpAdapter adaptador(HttpServer servidor) {
        return new PerfisCompradoresHttpAdapter(RestClient.builder(),
                "http://127.0.0.1:" + servidor.getAddress().getPort(), TOKEN);
    }

    private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (var saida = troca.getResponseBody()) { saida.write(bytes); }
    }
}
