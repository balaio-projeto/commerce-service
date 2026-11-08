package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.postgresql.PostgreSQLContainer;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(properties = "balaio.worker.intervalo-ms=3600000")
@Import(PedidoWorkflowPostgresTest.Postgres.class)
class PedidoWorkflowPostgresTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }

    @Autowired PedidoService pedidos;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean JwtDecoder decoder;

    @Test
    void checkoutPagamentoMediacaoEReplay() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "peca-unica-teste";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("100.00"),
                "unica", "atelie-teste", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA",
                new BigDecimal("100.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "pix",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-1");
        assertThat(pedidos.checkout(checkout, comprador, "compra-1")).isEqualTo(id);
        assertThatThrownBy(() -> pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 2)),
                "padrao", "pix", checkout.endereco()), comprador, "compra-1"))
                .isInstanceOf(PedidoException.class);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_reserva");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        pedidos.pagamento(id, "evento-pago-1", "aprovado");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("confirmado");
        pedidos.producao(id, artesao);
        assertThatThrownBy(() -> pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador."))
                .isInstanceOf(PedidoException.class)
                .hasMessageContaining("mediação");
        UUID decisao = UUID.randomUUID();
        pedidos.mediacao(id, decisao, "cancelar_reembolsar");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("encerramento_pendente");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolsado");
        assertThat(pedidos.mediacao(id, decisao, "cancelar_reembolsar").get("estado")).isEqualTo("reembolsado");
    }

    @Test
    void falhaDeRedeMantemEncerramentoPendenteAteLiberacao() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "peca-unica-falha";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("25.00"),
                "unica", "atelie-teste", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA",
                new BigDecimal("25.00"), artesao));
        when(catalogo.liberar(any(), eq(reserva)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn("LIBERADA");
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "pix",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-falha");
        pedidos.processar(id);
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador.");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("encerramento_pendente");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("cancelado");
    }

    @Test
    void pagamentoExpiraAntesDaReservaEWorkerLiberaPeca() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "peca-unica-prazo";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("30.00"),
                "unica", "atelie-teste", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA",
                new BigDecimal("30.00"), artesao));
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "pix",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-prazo");
        pedidos.processar(id);
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '15 minutes' WHERE id = ?", id);
        pedidos.processarPendentes();
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("falha_pagamento");
        assertThatThrownBy(() -> pedidos.pagamento(id, "evento-tardio", "aprovado"))
                .isInstanceOf(PedidoException.class);
    }

    @Test
    void encomendaNaoReservaENegarMediacaoMantemPedido() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "encomenda-teste";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "encomenda", "atelie-teste", artesao));
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 2)), "padrao", "pix",
                Map.of("cep", "52021-030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-encomenda");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        assertThat(pedidos.consultar(id, comprador).get("total")).isEqualTo(new BigDecimal("198.90"));
        pedidos.pagamento(id, "evento-encomenda", "aprovado");
        pedidos.processar(id);
        UUID decisao = UUID.randomUUID();
        assertThat(pedidos.mediacao(id, decisao, "negar").get("estado")).isEqualTo("confirmado");
        assertThat(pedidos.mediacao(id, decisao, "negar").get("estado")).isEqualTo("confirmado");
        assertThatThrownBy(() -> pedidos.checkout(new PedidoService.Checkout(checkout.itens(), "padrao", "pix",
                Map.of("cep", "inválido", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE")),
                comprador, "endereco-invalido")).isInstanceOf(PedidoException.class);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).reservar(eq(id), eq(slug));
    }

    @Test
    void timeoutAposReservaRemotaEReconciliadoPeloWorker() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "peca-timeout-remoto";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("40.00"),
                "unica", "atelie-teste", artesao));
        when(catalogo.reservar(any(), eq(slug)))
                .thenThrow(new ResourceAccessException("resposta perdida após criação remota"))
                .thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA", new BigDecimal("40.00"), artesao));
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "pix",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-timeout");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_reserva");
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '5 minutes' WHERE id = ?", id);
        pedidos.processarPendentes();
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(2)).reservar(eq(id), eq(slug));
    }

    @Test
    void cancelarERecusarEmDisputaTemUmEncerramentoVencedor() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "peca-disputa-encerramento";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("60.00"),
                "unica", "atelie-teste", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA",
                new BigDecimal("60.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "pix",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-disputa");
        pedidos.processar(id);
        pedidos.pagamento(id, "pagamento-disputa", "aprovado");
        pedidos.processar(id);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var buyer = executor.submit(() -> {
                start.await();
                try { pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador."); return true; }
                catch (PedidoException conflito) { return false; }
            });
            var seller = executor.submit(() -> {
                start.await();
                try { pedidos.encerrar(id, artesao, "recusado", "Motivo da recusa."); return true; }
                catch (PedidoException conflito) { return false; }
            });
            start.countDown();
            assertThat(buyer.get() ^ seller.get()).isTrue();
        }
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isIn("cancelado", "recusado");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).liberar(eq(id), eq(reserva));
    }

    @Test
    void consultasDoArtesaoRestringemPedidosAoProprioAtelie() {
        UUID comprador = UUID.randomUUID();
        UUID outroComprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID outroArtesao = UUID.randomUUID();
        when(catalogo.cotar("peca-do-atelie")).thenReturn(new CatalogoCliente.Cotacao(
                "peca-do-atelie", new BigDecimal("50.00"), "encomenda", "atelie-um", artesao));
        when(catalogo.cotar("peca-de-outro-atelie")).thenReturn(new CatalogoCliente.Cotacao(
                "peca-de-outro-atelie", new BigDecimal("70.00"), "encomenda", "atelie-dois", outroArtesao));
        var endereco = Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE");
        UUID primeiro = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item("peca-do-atelie", 1)),
                "padrao", "pix", endereco), comprador, "consulta-atelie-um");
        UUID segundo = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item("peca-de-outro-atelie", 1)),
                "padrao", "pix", endereco), outroComprador, "consulta-atelie-dois");
        pedidos.processar(primeiro);
        pedidos.processar(segundo);
        pedidos.pagamento(primeiro, "consulta-evento-um", "aprovado");
        pedidos.pagamento(segundo, "consulta-evento-dois", "aprovado");
        pedidos.processar(primeiro);
        pedidos.processar(segundo);
        assertThat(pedidos.listarPendentesArtesao(artesao)).extracting(p -> p.get("id")).containsExactly(primeiro);
        assertThat(pedidos.listarVendasArtesao(artesao)).extracting(p -> p.get("id")).containsExactly(primeiro);
        assertThat(pedidos.listarPendentesArtesao(outroArtesao)).extracting(p -> p.get("id")).containsExactly(segundo);
        assertThat(pedidos.listar(comprador)).extracting(p -> p.get("id")).containsExactly(primeiro);
        assertThatThrownBy(() -> pedidos.encerrar(primeiro, outroArtesao, "recusado", "Pedido de outro ateliê."))
                .isInstanceOf(PedidoException.class);
        pedidos.encerrar(primeiro, artesao, "recusado", "Não consigo produzir.");
        pedidos.processar(primeiro);
        assertThat(pedidos.listarPendentesArtesao(artesao)).isEmpty();
        assertThat(pedidos.listarVendasArtesao(artesao)).isEmpty();
    }
}
