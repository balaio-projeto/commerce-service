package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnderecoSalvoClientTest {
    @Test
    void usaEnderecoObtidoDoPerfilComTokenDoComprador() throws Exception {
        UUID id = UUID.randomUUID();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/v1/usuario/enderecos/" + id, troca -> {
            assertThat(troca.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-do-comprador");
            byte[] corpo = """
                    {"dados":{"id":"%s","rua":"Rua A, 1","bairro":"Boa Vista, Recife, PE","cep":"50000-000"},"erro":null}
                    """.formatted(id).getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) { saida.write(corpo); }
        });
        servidor.start();
        try {
            var cliente = new EnderecoSalvoClient("http://127.0.0.1:" + servidor.getAddress().getPort());
            assertThat(cliente.consultar(id, "token-do-comprador")).isEqualTo(Map.of(
                    "cep", "50000-000", "endereco", "Rua A, 1, Boa Vista",
                    "cidade", "Recife", "estado", "PE"));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void naoUsaEnderecoNaoEncontrado() throws Exception {
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            troca.sendResponseHeaders(404, -1);
            troca.close();
        });
        servidor.start();
        try {
            var cliente = new EnderecoSalvoClient("http://127.0.0.1:" + servidor.getAddress().getPort());
            assertThatThrownBy(() -> cliente.consultar(UUID.randomUUID(), "token"))
                    .isInstanceOfSatisfying(PedidoException.class,
                            erro -> assertThat(erro.codigo()).isEqualTo("ENDERECO_NAO_ENCONTRADO"));
        } finally {
            servidor.stop(0);
        }
    }
}
