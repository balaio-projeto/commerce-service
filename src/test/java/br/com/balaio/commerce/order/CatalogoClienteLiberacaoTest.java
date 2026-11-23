package br.com.balaio.commerce.order;

import br.com.balaio.commerce.integration.catalogo.CatalogoCliente;
import br.com.balaio.commerce.integration.catalogo.CatalogoPedidosHttpAdapter;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.ErroCatalogo;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.Falha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class CatalogoClienteLiberacaoTest {
    @Test
    void distingueConflitosPorOperacaoETrataFalhasDoCatalogo() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger();
        var conteudo = new AtomicReference<String>();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            byte[] corpo = conteudo.get().getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "application/json");
            troca.sendResponseHeaders(status.get(), corpo.length);
            try (var saida = troca.getResponseBody()) { saida.write(corpo); }
        });
        servidor.start();
        var cliente = new CatalogoCliente(RestClient.builder(), "http://127.0.0.1:" + servidor.getAddress().getPort(),
                "token-interno-de-teste-123456789012");
        var porta = new CatalogoPedidosHttpAdapter(cliente, JsonMapper.builder().build());
        UUID pedido = UUID.randomUUID();
        try {
            status.set(409);
            conteudo.set("{\"erro\":{\"codigo\":\"PECA_INDISPONIVEL\"}}");
            assertThatThrownBy(() -> porta.reservar(pedido, "vaso"))
                    .isInstanceOfSatisfying(ErroCatalogo.class, erro -> assertThat(erro.falha()).isEqualTo(Falha.RESERVA_AUSENTE));
            assertThatThrownBy(() -> porta.confirmar(pedido, UUID.randomUUID()))
                    .isInstanceOfSatisfying(ErroCatalogo.class, erro -> assertThat(erro.falha()).isEqualTo(Falha.CONFLITO));
            conteudo.set("corpo inválido");
            assertThatThrownBy(() -> porta.reservar(pedido, "vaso"))
                    .isInstanceOfSatisfying(ErroCatalogo.class, erro -> assertThat(erro.falha()).isEqualTo(Falha.CONFLITO));

            for (int codigo : new int[] {401, 403}) {
                status.set(codigo);
                assertThatThrownBy(() -> porta.cotar("vaso"))
                        .isInstanceOfSatisfying(ErroCatalogo.class, erro -> assertThat(erro.falha()).isEqualTo(Falha.INDISPONIVEL));
            }
            status.set(200);
            conteudo.set("{\"dados\":null}");
            assertThatThrownBy(() -> porta.reservar(pedido, "vaso"))
                    .isInstanceOfSatisfying(ErroCatalogo.class, erro -> assertThat(erro.falha()).isEqualTo(Falha.RESPOSTA_INVALIDA));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void soAceitaConfirmacaoDaReservaEDoPedidoSolicitados() throws Exception {
        UUID pedido = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        var conteudo = new AtomicReference<String>();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/v1/reservas/" + reserva + "/liberar", requisicao -> {
            assertThat(requisicao.getRequestHeaders().getFirst("X-Balaio-Service-Token"))
                    .isEqualTo("token-interno-de-teste-123456789012");
            assertThat(new String(requisicao.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("liberar:" + reserva, pedido.toString());
            byte[] resposta = conteudo.get().getBytes(StandardCharsets.UTF_8);
            requisicao.getResponseHeaders().set("Content-Type", "application/json");
            requisicao.sendResponseHeaders(200, resposta.length);
            requisicao.getResponseBody().write(resposta);
            requisicao.close();
        });
        servidor.start();
        try {
            var cliente = new CatalogoCliente(RestClient.builder(), "http://127.0.0.1:" + servidor.getAddress().getPort(),
                    "token-interno-de-teste-123456789012");
            conteudo.set(resposta(reserva, pedido, "LIBERADA"));
            assertThat(cliente.liberar(pedido, reserva)).isEqualTo("LIBERADA");
            conteudo.set(resposta(reserva, pedido, "EXPIRADA"));
            assertThat(cliente.liberar(pedido, reserva)).isEqualTo("EXPIRADA");
            for (String invalida : new String[] {
                    resposta(UUID.randomUUID(), pedido, "LIBERADA"),
                    resposta(reserva, UUID.randomUUID(), "LIBERADA"),
                    resposta(reserva, pedido, "RESERVADA") }) {
                conteudo.set(invalida);
                assertThatThrownBy(() -> cliente.liberar(pedido, reserva))
                        .isInstanceOf(IllegalStateException.class);
            }
        } finally {
            servidor.stop(0);
        }
    }

    private String resposta(UUID reserva, UUID pedido, String estado) {
        return "{\"dados\":{\"id\":\"" + reserva + "\",\"pedidoId\":\"" + pedido
                + "\",\"estado\":\"" + estado + "\"},\"erro\":null}";
    }
}
