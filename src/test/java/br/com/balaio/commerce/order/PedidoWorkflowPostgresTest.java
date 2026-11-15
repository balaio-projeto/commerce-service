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
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import br.com.balaio.commerce.payment.CheckoutProClient;
import br.com.balaio.commerce.payment.CheckoutProService;

@EnabledIfEnvironmentVariable(named = "COMMERCE_POSTGRES_TEST", matches = "true")
@SpringBootTest(properties = {"balaio.worker.intervalo-ms=3600000",
        "payment.mercadopago.worker-interval-ms=3600000", "payment.mercadopago.environment=sandbox",
        "payment.mercadopago.base-url=https://api.mercadopago.com",
        "payment.mercadopago.access-token=APP_USR-test-placeholder",
        "payment.mercadopago.webhook-secret=webhook-test-placeholder",
        "payment.mercadopago.notification-url=https://test.example/webhook",
        "payment.mercadopago.frontend-return-url=https://test.example"})
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
    @MockitoBean CheckoutProClient provedor;
    @MockitoBean JwtDecoder decoder;
    @Autowired CheckoutProService pagamentos;
    @Autowired ObjectMapper json;

    @org.junit.jupiter.api.BeforeEach
    void preferenciaMockada() {
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(invocacao -> {
            UUID id = invocacao.getArgument(0);
            return new CheckoutProClient.Preferencia("pref-" + id,
                    "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id);
        });
    }

    private void preparar(UUID id) {
        pedidos.processar(id);
        pagamentos.preparar(id);
    }

    private void aprovar(UUID id, String pagamentoId) {
        mockPagamento(id, pagamentoId, "approved");
        pagamentos.aplicarPagamento(pagamentoId);
    }

    private void mockPagamento(UUID id, String pagamentoId, String estado) {
        BigDecimal total = (BigDecimal) pedidos.consultarInterno(id).get("total");
        when(provedor.pagamento(pagamentoId)).thenReturn(new CheckoutProClient.Pagamento(pagamentoId,
                id.toString(), total, "BRL", estado, false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new CheckoutProClient.Ordem(
                "pref-" + id, id.toString()));
    }

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
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-1");
        assertThat(pedidos.checkout(checkout, comprador, "compra-1")).isEqualTo(id);
        assertThatThrownBy(() -> pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 2)),
                "padrao", "checkout_pro", checkout.endereco()), comprador, "compra-1"))
                .isInstanceOf(PedidoException.class);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_reserva");
        preparar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        aprovar(id, "evento-pago-1");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("confirmado");
        pedidos.producao(id, artesao);
        assertThatThrownBy(() -> pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador."))
                .isInstanceOf(PedidoException.class)
                .hasMessageContaining("mediação");
        UUID decisao = UUID.randomUUID();
        pedidos.mediacao(id, decisao, "cancelar_reembolsar");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolso_pendente");
        mockPagamento(id, "evento-pago-1", "refunded");
        pagamentos.aplicarPagamento("evento-pago-1");
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
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-falha");
        preparar(id);
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador.");
        mockPagamento(id, "pagamento-cancelado", "pending");
        pagamentos.aplicarPagamento("pagamento-cancelado");
        org.mockito.Mockito.doThrow(new ResourceAccessException("timeout"))
                .doNothing().when(provedor).cancelar("pagamento-cancelado");
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("cancelamento_pendente");
        mockPagamento(id, "pagamento-cancelado", "cancelled");
        pagamentos.aplicarPagamento("pagamento-cancelado");
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
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-prazo");
        preparar(id);
        jdbc.update("UPDATE pagamento_intencoes SET criado_em = now() - interval '17 minutes' WHERE pedido_id = ?", id);
        pagamentos.reconciliar();
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("falha_pagamento");
        org.mockito.Mockito.verify(catalogo).liberar(id, reserva);
    }

    @Test
    void encomendaNaoReservaENegarMediacaoMantemPedido() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "encomenda-teste";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "encomenda", "atelie-teste", artesao));
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 2)), "padrao", "checkout_pro",
                Map.of("cep", "52021-030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-encomenda");
        preparar(id);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        assertThat(pedidos.consultar(id, comprador).get("total")).isEqualTo(new BigDecimal("198.90"));
        aprovar(id, "evento-encomenda");
        UUID decisao = UUID.randomUUID();
        assertThat(pedidos.mediacao(id, decisao, "negar").get("estado")).isEqualTo("confirmado");
        assertThat(pedidos.mediacao(id, decisao, "negar").get("estado")).isEqualTo("confirmado");
        assertThatThrownBy(() -> pedidos.checkout(new PedidoService.Checkout(checkout.itens(), "padrao", "checkout_pro",
                Map.of("cep", "inválido", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE")),
                comprador, "endereco-invalido")).isInstanceOf(PedidoException.class);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).reservar(eq(id), eq(slug));
        mockPagamento(id, "evento-encomenda", "refunded");
        pagamentos.aplicarPagamento("evento-encomenda");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolsado");
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
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "checkout_pro",
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
    void checkoutConcorrenteCompartilhaPedidoReservaEPreferencia() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "concorrente-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("50.00"),
                "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(reserva, "RESERVADA",
                new BigDecimal("50.00"), artesao));
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)),
                "padrao", "checkout_pro", endereco());
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeiro = executor.submit(() -> {
                inicio.await();
                UUID id = pedidos.checkout(checkout, comprador, "mesma-chave-" + slug);
                pedidos.processar(id);
                pagamentos.preparar(id);
                return id;
            });
            var segundo = executor.submit(() -> {
                inicio.await();
                UUID id = pedidos.checkout(checkout, comprador, "mesma-chave-" + slug);
                pedidos.processar(id);
                pagamentos.preparar(id);
                return id;
            });
            inicio.countDown();
            assertThat(primeiro.get()).isEqualTo(segundo.get());
        }
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).reservar(any(), eq(slug));
        org.mockito.Mockito.verify(provedor, org.mockito.Mockito.times(1)).criar(any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE comprador_id = ?",
                Integer.class, comprador)).isEqualTo(1);
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
        var checkout = new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-disputa");
        preparar(id);
        aprovar(id, "pagamento-disputa");
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
        mockPagamento(id, "pagamento-disputa", "refunded");
        pagamentos.aplicarPagamento("pagamento-disputa");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolsado");
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
                "padrao", "checkout_pro", endereco), comprador, "consulta-atelie-um");
        UUID segundo = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item("peca-de-outro-atelie", 1)),
                "padrao", "checkout_pro", endereco), outroComprador, "consulta-atelie-dois");
        preparar(primeiro);
        preparar(segundo);
        aprovar(primeiro, "consulta-evento-um");
        aprovar(segundo, "consulta-evento-dois");
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

    @Test
    void webhookAssinadoConsultaProvedorERepeticaoNaoDuplicaTransicao() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "peca-webhook-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("70.00"),
                "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "webhook-" + UUID.randomUUID());
        preparar(id);
        mockPagamento(id, "123456789", "approved");
        String requestId = "request-" + UUID.randomUUID();
        String dataId = "123456789";
        var aviso = json.readTree("{\"type\":\"payment\",\"live_mode\":false,\"data\":{\"id\":\"123456789\"}}");
        assertThatThrownBy(() -> pagamentos.receber("ts=1,v1=invalid", requestId, dataId, aviso))
                .isInstanceOf(PedidoException.class);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
        String assinatura = assinatura(requestId, dataId);
        assertThatThrownBy(() -> pagamentos.receber(assinatura, requestId, "123456788", aviso))
                .isInstanceOf(PedidoException.class);
        pagamentos.receber(assinatura, requestId, dataId, aviso);
        pagamentos.receber(assinatura, requestId, dataId, aviso);
        pagamentos.processarNotificacoes();
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("confirmado");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_notificacoes WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void divergenciaDeValorNaoConfirmaETimeoutDaPreferenciaNaoDuplicaPost() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "peca-divergente-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "ambigua-" + UUID.randomUUID());
        pedidos.processar(id);
        when(provedor.criar(eq(id), any(), any())).thenThrow(new ResourceAccessException("resposta perdida"));
        pagamentos.preparar(id);
        pagamentos.preparar(id);
        org.mockito.Mockito.verify(provedor, org.mockito.Mockito.times(1)).criar(eq(id), any(), any());
        assertThat(pagamentos.complementar(id, pedidos.consultar(id, comprador)).get("checkoutUrl")).isNull();
        when(provedor.buscar(id.toString())).thenReturn(List.of(new CheckoutProClient.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id)));
        pagamentos.preparar(id);
        when(provedor.pagamento("999999")).thenReturn(new CheckoutProClient.Pagamento("999999", id.toString(),
                new BigDecimal("1.00"), "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new CheckoutProClient.Ordem("pref-" + id, id.toString()));
        assertThatThrownBy(() -> pagamentos.aplicarPagamento("999999")).isInstanceOf(IllegalStateException.class);
        when(provedor.pagamento("999999")).thenReturn(new CheckoutProClient.Pagamento("999999", id.toString(),
                (BigDecimal) pedidos.consultarInterno(id).get("total"), "USD", "approved", false,
                "ordem-" + id, BigDecimal.ZERO));
        assertThatThrownBy(() -> pagamentos.aplicarPagamento("999999")).isInstanceOf(IllegalStateException.class);
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("aguardando_pagamento");
    }

    @Test
    void reembolsoIntegralConfirmadoPorValorEncerraPedidoEEventoLiveNaoEntraNaInbox() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "reembolso-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new PedidoService.Checkout(List.of(new PedidoService.Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "reembolso-" + UUID.randomUUID());
        preparar(id);
        aprovar(id, "123456780");
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador.");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolso_pendente");
        BigDecimal total = (BigDecimal) pedidos.consultarInterno(id).get("total");
        when(provedor.pagamento("123456780")).thenReturn(new CheckoutProClient.Pagamento("123456780",
                id.toString(), total, "BRL", "approved", false, "ordem-" + id, total));
        pagamentos.aplicarPagamento("123456780");
        assertThat(pedidos.consultar(id, comprador).get("estado")).isEqualTo("reembolsado");

        String requestId = "live-" + UUID.randomUUID();
        var live = json.readTree("{\"type\":\"payment\",\"live_mode\":true,\"data\":{\"id\":\"123456780\"}}");
        assertThatThrownBy(() -> pagamentos.receber(assinatura(requestId, "123456780"),
                requestId, "123456780", live)).isInstanceOf(PedidoException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_notificacoes WHERE request_id = ?",
                Integer.class, requestId)).isZero();
    }

    private Map<String, String> endereco() {
        return Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE");
    }

    private String assinatura(String requestId, String dataId) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("webhook-test-placeholder".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String manifesto = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        return "ts=" + ts + ",v1=" + HexFormat.of().formatHex(mac.doFinal(manifesto.getBytes(StandardCharsets.UTF_8)));
    }
}
