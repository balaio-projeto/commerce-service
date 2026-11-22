package br.com.balaio.commerce.order;

import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.api.PedidoResponse;
import br.com.balaio.commerce.order.api.PedidosController;
import br.com.balaio.commerce.payment.api.WebhookInvalido;
import br.com.balaio.commerce.shared.security.AcessoNegado;

import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.PedidoService;
import br.com.balaio.commerce.order.application.PedidoParaPagamentoService;
import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase;

import br.com.balaio.commerce.integration.catalogo.CatalogoCliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;
import br.com.balaio.commerce.payment.application.CheckoutProService;
import br.com.balaio.commerce.payment.api.PaymentWebhookController;

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
    @Autowired CheckoutUseCase checkout;
    @Autowired PedidosController controller;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CatalogoCliente catalogo;
    @MockitoBean PagamentoGateway provedor;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean CheckoutProService pagamentos;
    @MockitoSpyBean PedidoParaPagamentoService pedidoFinanceiro;
    @Autowired PaymentWebhookController webhook;
    @Autowired ObjectMapper json;

    @Test
    void pedidoAnteriorAMigracaoMantemIndicadorAposNovaEtapa() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "legado-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(
                slug, new BigDecimal("40.00"), "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", Map.of("cep", "52021030", "endereco", "Rua", "cidade", "Recife",
                        "estado", "PE")), comprador, slug);
        assertThat(jdbc.queryForObject("SELECT historico_incompleto FROM pedidos WHERE id = ?", Boolean.class, id))
                .isFalse();
        jdbc.update("UPDATE pedidos SET estado = 'confirmado' WHERE id = ?", id);
        jdbc.update("DELETE FROM pedido_etapas WHERE pedido_id = ?", id);
        jdbc.update("UPDATE pedidos SET historico_incompleto = true WHERE id = ?", id);
        var detalhe = pedidos.consultar(id, comprador).pedido();
        assertThat(detalhe.historicoInferido()).isEqualTo(true);
        var etapas = detalhe.etapas();
        assertThat(etapas).hasSize(1);
        assertThat(etapas.getFirst().estado()).isEqualTo("confirmado");
        assertThat(etapas.getFirst().criadaEm()).isNull();
        jdbc.update("UPDATE pedidos SET estado = 'producao' WHERE id = ?", id);
        var depois = pedidos.consultar(id, comprador).pedido();
        assertThat(depois.historicoInferido()).isEqualTo(true);
        var novasEtapas = depois.etapas();
        assertThat(novasEtapas).hasSize(1);
        assertThat(novasEtapas.getFirst().estado()).isEqualTo("producao");
    }

    @org.junit.jupiter.api.BeforeEach
    void preferenciaMockada() {
        when(provedor.buscar(any())).thenReturn(List.of());
        when(provedor.criar(any(), any(), any())).thenAnswer(invocacao -> {
            UUID id = invocacao.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT estado FROM pagamento_intencoes WHERE pedido_id = ?",
                    String.class, id)).isEqualTo("CRIANDO");
            return new PagamentoGateway.Preferencia("pref-" + id,
                    "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id);
        });
    }

    private void preparar(UUID id) {
        pedidos.processar(id);
        pagamentos.preparar(id);
    }

    private br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase.EstadoPagamento estadoPagamento(UUID id) {
        var pedido = pedidos.consultarInterno(id);
        return pagamentos.consultarPagamento(id, pedido.estado(), pedido.total());
    }

    private void aprovar(UUID id, String pagamentoId) {
        mockPagamento(id, pagamentoId, "approved");
        pagamentos.aplicarPagamento(pagamentoId);
    }

    private void mockPagamento(UUID id, String pagamentoId, String estado) {
        BigDecimal total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento(pagamentoId)).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PagamentoGateway.Pagamento(pagamentoId, id.toString(), total,
                    "BRL", estado, false, "ordem-" + id, BigDecimal.ZERO);
        });
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem(
                "pref-" + id, id.toString()));
    }

    @Test
    void recuperacaoSoMarcaSemReservaComCodigoExplicitoDoCatalogo() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "reserva-ausente";
        when(catalogo.cotar(slug)).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new CatalogoCliente.Cotacao(slug, new BigDecimal("30.00"), "unica", "atelie", artesao);
        });
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "sem-reserva");
        pedidos.encerrar(id, comprador, "cancelado", null);
        when(catalogo.reservar(id, slug)).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw conflitoCatalogo("corpo inválido");
        }).thenThrow(conflitoCatalogo("{\"erro\":{\"codigo\":\"PECA_INDISPONIVEL\"}}"));

        pedidos.processar(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("encerramento_pendente");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ?",
                String.class, id)).isNull();
        pedidos.processar(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ?",
                String.class, id)).isEqualTo("SEM_RESERVA");
    }

    private HttpClientErrorException conflitoCatalogo(String corpo) {
        return HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                corpo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void falhaNoTriggerDesfazIntencaoPedidoEEtapaEmConjunto() {
        String slug = "peca-rollback";
        UUID comprador = UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(
                slug, new BigDecimal("50.00"), "encomenda", "atelie", UUID.randomUUID()));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "rollback-pagamento");
        preparar(id);
        mockPagamento(id, "375601", "approved");
        int etapas = jdbc.queryForObject("SELECT count(*) FROM pedido_etapas WHERE pedido_id = ?", Integer.class, id);
        var antes = jdbc.queryForMap("SELECT pagamento_id, status_provedor, valor_reembolsado FROM pagamento_intencoes "
                + "WHERE pedido_id = ?", id);

        jdbc.execute("""
                ALTER TABLE pedido_etapas ADD CONSTRAINT teste_rejeita_etapa
                CHECK (pedido_id <> '%s' OR estado <> 'confirmacao_pendente')
                """.formatted(id));
        try {
            assertThatThrownBy(() -> pagamentos.aplicarPagamento("375601"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForMap("SELECT pagamento_id, status_provedor, valor_reembolsado FROM pagamento_intencoes "
                    + "WHERE pedido_id = ?", id)).isEqualTo(antes);
            assertThat(jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id))
                    .isEqualTo("aguardando_pagamento");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_etapas WHERE pedido_id = ?", Integer.class, id))
                    .isEqualTo(etapas);
        } finally {
            jdbc.execute("ALTER TABLE pedido_etapas DROP CONSTRAINT teste_rejeita_etapa");
        }
        mockPagamento(id, "375601", "novo_estado_provedor");
        pagamentos.aplicarPagamento("375601");
        assertThat(jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("aguardando_pagamento");
        assertThat(jdbc.queryForObject("SELECT status_provedor FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo("novo_estado_provedor");
        aprovar(id, "375601");
        assertThat(jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("confirmado");
    }

    @Test
    void artesaoSoAtualizaPedidoProprioConfirmadoECompradorVeEstadoPersistido() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID outroArtesao = UUID.randomUUID();
        String slug = "gestao-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("75.00"), "encomenda", "atelie", artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "gestao-" + slug);
        assertThat(pedidos.listarPendentesArtesao(artesao)).isEmpty();
        assertThatThrownBy(() -> pedidos.producao(id, artesao)).isInstanceOf(PedidoException.class);
        preparar(id);
        aprovar(id, "pagamento-" + slug);
        assertThat(pedidos.listarPendentesArtesao(artesao)).extracting(pedido -> pedido.id()).containsExactly(id);
        assertThat(pedidos.listarPendentesArtesao(outroArtesao)).isEmpty();
        assertThatThrownBy(() -> pedidos.producao(id, outroArtesao)).isInstanceOf(PedidoException.class);
        var jwtComprador = jwt(comprador, "comprador");
        var jwtArtesao = jwt(artesao, "artesao");
        assertThatThrownBy(() -> controller.estado(id, new PedidosController.Estado("producao"), jwtComprador))
                .isInstanceOf(AcessoNegado.class);
        assertThatThrownBy(() -> controller.estado(id, new PedidosController.Estado("enviado"), jwtArtesao))
                .isInstanceOf(PedidoException.class);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("confirmado");
        assertThat(((PedidoResponse) controller.estado(id, new PedidosController.Estado("producao"), jwtArtesao)
                .dados()).estado()).isEqualTo("producao");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("producao");
        assertThat(pedidos.listarPendentesArtesao(artesao)).isEmpty();
        assertThatThrownBy(() -> pedidos.producao(id, artesao)).isInstanceOf(PedidoException.class);
    }

    private org.springframework.security.oauth2.jwt.Jwt jwt(UUID id, String papel) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("teste")
                .header("alg", "none").subject(id.toString()).claim("papel", papel).build();
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
        var checkout = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-1");
        assertThat(jdbc.queryForList("SELECT estado FROM pedido_etapas WHERE pedido_id = ? ORDER BY id",
                String.class, id)).containsExactly("aguardando_reserva");
        assertThat(pedidos.checkout(checkout, comprador, "compra-1")).isEqualTo(id);
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("999.00"),
                "unica", "atelie-teste", artesao));
        assertThat(pedidos.consultar(id, comprador).pedido().itens().getFirst().preco()).isEqualTo(new BigDecimal("100.00"));
        assertThatThrownBy(() -> pedidos.checkout(new Checkout(List.of(new Item(slug, 2)),
                "padrao", "checkout_pro", checkout.endereco()), comprador, "compra-1"))
                .isInstanceOf(PedidoException.class);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_reserva");
        preparar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_pagamento");
        aprovar(id, "evento-pago-1");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("confirmado");
        pedidos.producao(id, artesao);
        assertThat(jdbc.queryForList("SELECT estado FROM pedido_etapas WHERE pedido_id = ? ORDER BY id",
                String.class, id)).containsExactly("aguardando_reserva", "aguardando_pagamento",
                        "confirmacao_pendente", "confirmado", "producao");
        assertThatThrownBy(() -> pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador."))
                .isInstanceOf(PedidoException.class)
                .hasMessageContaining("mediação");
        UUID decisao = UUID.randomUUID();
        pedidos.mediacao(id, decisao, "cancelar_reembolsar");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("mediacao_cancelar_reembolsar");
        mockPagamento(id, "evento-pago-1", "refunded");
        pagamentos.aplicarPagamento("evento-pago-1");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolsado");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("mediacao_cancelar_reembolsar");
        assertThat(pedidos.mediacao(id, decisao, "cancelar_reembolsar").estado()).isEqualTo("reembolsado");
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
        var checkout = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-falha");
        preparar(id);
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador.");
        mockPagamento(id, "pagamento-cancelado", "pending");
        pagamentos.aplicarPagamento("pagamento-cancelado");
        org.mockito.Mockito.doThrow(new ResourceAccessException("timeout"))
                .doNothing().when(provedor).cancelar("pagamento-cancelado");
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelamento_pendente");
        mockPagamento(id, "pagamento-cancelado", "cancelled");
        pagamentos.aplicarPagamento("pagamento-cancelado");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
    }

    @Test
    void recusaAguardaReembolsoEConservaCausaAposTimeout() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "recusa-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("70.00"), "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("70.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, slug);
        preparar(id);
        aprovar(id, "pagamento-" + slug);
        assertThatThrownBy(() -> pedidos.encerrar(id, artesao, "recusado", "não"))
                .isInstanceOf(PedidoException.class);
        pedidos.encerrar(id, artesao, "recusado", "Não consigo produzir.");
        assertThat(pedidos.encerrar(id, artesao, "recusado", "Não consigo produzir.").estado())
                .isEqualTo("reembolso_pendente");
        assertThatThrownBy(() -> pedidos.encerrar(id, comprador, "cancelado", "Cancelado."))
                .isInstanceOf(PedidoException.class);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(eq(id), eq(reserva));
        org.mockito.Mockito.doThrow(new ResourceAccessException("timeout"))
                .when(provedor).reembolsar("pagamento-" + slug, id);
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(eq(id), eq(reserva));
        mockPagamento(id, "pagamento-" + slug, "refunded");
        pagamentos.aplicarPagamento("pagamento-" + slug);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("recusado");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("recusado");
        org.mockito.Mockito.verify(catalogo).liberar(id, reserva);
        mockPagamento(id, "pagamento-" + slug, "approved");
        pagamentos.aplicarPagamento("pagamento-" + slug);
        mockPagamento(id, "pagamento-" + slug, "pending");
        pagamentos.aplicarPagamento("pagamento-" + slug);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("recusado");
        assertThat(estadoPagamento(id).estado())
                .isEqualTo("refunded");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).liberar(id, reserva);
    }

    @Test
    void aprovacaoDuranteCancelamentoNaoLiberaAntesDoEstorno() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "corrida-pagamento-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("45.00"), "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("45.00"), artesao));
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, slug);
        preparar(id);
        pedidos.encerrar(id, comprador, "cancelado", null);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelamento_pendente");
        String pagamentoId = "pagamento-" + slug;
        mockPagamento(id, pagamentoId, "approved");
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(id, reserva);
        org.mockito.Mockito.doThrow(new ResourceAccessException("timeout"))
                .when(provedor).reembolsar(pagamentoId, id);
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(id, reserva);
        mockPagamento(id, pagamentoId, "refunded");
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        org.mockito.Mockito.verify(catalogo).liberar(id, reserva);
    }

    @Test
    void liberacaoParcialDeVariosItensSoEncerraAposRetry() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        String slugA = "multiplo-a-" + UUID.randomUUID();
        String slugB = "multiplo-b-" + UUID.randomUUID();
        String comum = "multiplo-comum-" + UUID.randomUUID();
        for (String slug : List.of(slugA, slugB))
            when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                    new BigDecimal("30.00"), "unica", "atelie", artesao));
        when(catalogo.cotar(comum)).thenReturn(new CatalogoCliente.Cotacao(comum,
                new BigDecimal("10.00"), "encomenda", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slugA))).thenReturn(new CatalogoCliente.Reserva(
                primeira, "RESERVADA", new BigDecimal("30.00"), artesao));
        when(catalogo.reservar(any(), eq(slugB))).thenReturn(new CatalogoCliente.Reserva(
                segunda, "RESERVADA", new BigDecimal("30.00"), artesao));
        when(catalogo.liberar(any(), eq(primeira))).thenReturn("LIBERADA");
        when(catalogo.liberar(any(), eq(segunda)))
                .thenThrow(new ResourceAccessException("resposta perdida")).thenReturn("LIBERADA");
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slugA, 1),
                new Item(slugB, 1), new Item(comum, 1)),
                "padrao", "checkout_pro", endereco()), comprador, slugA);
        preparar(id);
        pedidos.encerrar(id, comprador, "cancelado", null);
        mockPagamento(id, "pagamento-" + slugA, "cancelled");
        pagamentos.aplicarPagamento("pagamento-" + slugA);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("encerramento_pendente");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ? AND slug = ?",
                String.class, id, slugA)).isEqualTo("LIBERADA");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ? AND slug = ?",
                String.class, id, slugB)).isNull();
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).liberar(id, primeira);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(2)).liberar(id, segunda);
    }

    @Test
    void cancelamentoRecuperaReservaExpiradaSemCriarOutra() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "expirada-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug,
                new BigDecimal("20.00"), "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "EXPIRADA", new BigDecimal("20.00"), artesao));
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, slug);
        pedidos.encerrar(id, comprador, "cancelado", null);
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        assertThat(jdbc.queryForObject("SELECT reserva_id FROM pedido_itens WHERE pedido_id = ? AND slug = ?",
                UUID.class, id, slug)).isEqualTo(reserva);
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ? AND slug = ?",
                String.class, id, slug)).isEqualTo("EXPIRADA");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).liberar(eq(id), eq(reserva));
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
        var checkout = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-prazo");
        preparar(id);
        jdbc.update("UPDATE pagamento_intencoes SET criado_em = now() - interval '17 minutes' WHERE pedido_id = ?", id);
        pagamentos.reconciliar();
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("falha_pagamento");
        org.mockito.Mockito.verify(catalogo).liberar(id, reserva);
    }

    @Test
    void encomendaNaoReservaENegarMediacaoMantemPedido() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        String slug = "encomenda-teste";
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "encomenda", "atelie-teste", artesao));
        var checkout = new Checkout(List.of(new Item(slug, 2)), "padrao", "checkout_pro",
                Map.of("cep", "52021-030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-encomenda");
        preparar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_pagamento");
        assertThat(pedidos.consultar(id, comprador).pedido().total()).isEqualTo(new BigDecimal("198.90"));
        aprovar(id, "evento-encomenda");
        UUID decisao = UUID.randomUUID();
        assertThat(pedidos.mediacao(id, decisao, "negar").estado()).isEqualTo("confirmado");
        assertThat(pedidos.mediacao(id, decisao, "negar").estado()).isEqualTo("confirmado");
        assertThatThrownBy(() -> pedidos.mediacao(id, UUID.randomUUID(), "cancelar_reembolsar"))
                .isInstanceOf(PedidoException.class);
        assertThatThrownBy(() -> pedidos.checkout(new Checkout(checkout.itens(), "padrao", "checkout_pro",
                Map.of("cep", "inválido", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE")),
                comprador, "endereco-invalido")).isInstanceOf(PedidoException.class);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.never()).reservar(eq(id), eq(slug));
        mockPagamento(id, "evento-encomenda", "refunded");
        pagamentos.aplicarPagamento("evento-encomenda");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolsado");
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
        var checkout = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
                Map.of("cep", "52021030", "endereco", "Rua de teste", "cidade", "Recife", "estado", "PE"));
        UUID id = pedidos.checkout(checkout, comprador, "compra-timeout");
        pedidos.processar(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_reserva");
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '5 minutes' WHERE id = ?", id);
        pedidos.processarPendentes();
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_pagamento");
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
        var checkout = new Checkout(List.of(new Item(slug, 1)),
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
        var checkout = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro",
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
        String causa = jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id);
        assertThat(causa).isIn("cancelado", "recusado");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo(causa);
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
        UUID primeiro = pedidos.checkout(new Checkout(List.of(new Item("peca-do-atelie", 1)),
                "padrao", "checkout_pro", endereco), comprador, "consulta-atelie-um");
        UUID segundo = pedidos.checkout(new Checkout(List.of(new Item("peca-de-outro-atelie", 1)),
                "padrao", "checkout_pro", endereco), outroComprador, "consulta-atelie-dois");
        preparar(primeiro);
        preparar(segundo);
        aprovar(primeiro, "consulta-evento-um");
        aprovar(segundo, "consulta-evento-dois");
        assertThat(pedidos.listarPendentesArtesao(artesao)).extracting(pedido -> pedido.id()).containsExactly(primeiro);
        assertThat(pedidos.listarVendasArtesao(artesao)).extracting(pedido -> pedido.id()).containsExactly(primeiro);
        assertThat(pedidos.listarPendentesArtesao(outroArtesao)).extracting(pedido -> pedido.id()).containsExactly(segundo);
        assertThat(pedidos.listar(comprador)).extracting(pedido -> pedido.id()).containsExactly(primeiro);
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
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "webhook-" + UUID.randomUUID());
        preparar(id);
        mockPagamento(id, "123456789", "approved");
        String requestId = "request-" + UUID.randomUUID();
        String dataId = "123456789";
        var aviso = json.readTree("{\"type\":\"payment\",\"live_mode\":false,\"data\":{\"id\":\"123456789\"}}");
        assertThatThrownBy(() -> webhook.receber("ts=1,v1=invalid", requestId, dataId, aviso))
                .isInstanceOf(WebhookInvalido.class);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_pagamento");
        String assinatura = assinatura(requestId, dataId);
        assertThatThrownBy(() -> webhook.receber(assinatura, requestId, "123456788", aviso))
                .isInstanceOf(WebhookInvalido.class);
        webhook.receber(assinatura, requestId, dataId, aviso);
        webhook.receber(assinatura, requestId, dataId, aviso);
        pagamentos.processarNotificacoes();
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("confirmado");
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
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "ambigua-" + UUID.randomUUID());
        pedidos.processar(id);
        org.mockito.Mockito.doThrow(new ResourceAccessException("resposta perdida"))
                .when(provedor).criar(eq(id), any(), any());
        pagamentos.preparar(id);
        pagamentos.preparar(id);
        org.mockito.Mockito.verify(provedor, org.mockito.Mockito.times(1)).criar(eq(id), any(), any());
        assertThat(estadoPagamento(id).checkoutUrl()).isNull();
        when(provedor.buscar(id.toString())).thenReturn(List.of(new PagamentoGateway.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id)));
        pagamentos.preparar(id);
        when(provedor.pagamento("999999")).thenReturn(new PagamentoGateway.Pagamento("999999", id.toString(),
                new BigDecimal("1.00"), "BRL", "approved", false, "ordem-" + id, BigDecimal.ZERO));
        when(provedor.ordem("ordem-" + id)).thenReturn(new PagamentoGateway.Ordem("pref-" + id, id.toString()));
        assertThatThrownBy(() -> pagamentos.aplicarPagamento("999999")).isInstanceOf(IllegalStateException.class);
        when(provedor.pagamento("999999")).thenReturn(new PagamentoGateway.Pagamento("999999", id.toString(),
                pedidos.consultarInterno(id).total(), "USD", "approved", false,
                "ordem-" + id, BigDecimal.ZERO));
        assertThatThrownBy(() -> pagamentos.aplicarPagamento("999999")).isInstanceOf(IllegalStateException.class);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("aguardando_pagamento");
    }

    @Test
    void reembolsoIntegralConfirmadoPorValorEncerraPedidoEEventoLiveNaoEntraNaInbox() throws Exception {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "reembolso-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("80.00"),
                "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("80.00"), artesao));
        when(catalogo.confirmar(any(), eq(reserva))).thenReturn("CONSUMIDA");
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, "reembolso-" + UUID.randomUUID());
        preparar(id);
        aprovar(id, "123456780");
        pedidos.encerrar(id, comprador, "cancelado", "Cancelado pelo comprador.");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        BigDecimal total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento("123456780")).thenReturn(new PagamentoGateway.Pagamento("123456780",
                id.toString(), total, "BRL", "approved", false, "ordem-" + id, total));
        pagamentos.aplicarPagamento("123456780");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        assertThat(estadoPagamento(id).estado())
                .isEqualTo("refunded");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("cancelado");
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).liberar(id, reserva);

        mockPagamento(id, "123456780", "approved");
        pagamentos.aplicarPagamento("123456780");
        pagamentos.tentarOperacao(id);
        mockPagamento(id, "123456780", "pending");
        pagamentos.aplicarPagamento("123456780");
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        assertThat(estadoPagamento(id).estado())
                .isEqualTo("refunded");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("cancelado");
        assertThat(jdbc.queryForObject("SELECT valor_reembolsado FROM pagamento_intencoes WHERE pedido_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo(total);
        org.mockito.Mockito.verify(provedor, org.mockito.Mockito.never()).reembolsar("123456780", id);
        org.mockito.Mockito.verify(catalogo, org.mockito.Mockito.times(1)).liberar(id, reserva);

        String requestId = "live-" + UUID.randomUUID();
        var live = json.readTree("{\"type\":\"payment\",\"live_mode\":true,\"data\":{\"id\":\"123456780\"}}");
        assertThatThrownBy(() -> webhook.receber(assinatura(requestId, "123456780"),
                requestId, "123456780", live)).isInstanceOf(WebhookInvalido.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_notificacoes WHERE request_id = ?",
                Integer.class, requestId)).isZero();
    }

    @Test
    void cancelamentoObsoletoNaoSobrescreveAprovacaoDepoisDaVerificacaoDoPrazo() throws Exception {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        preparar(id);
        pedidos.encerrar(id, compra.comprador(), "cancelado", "Cancelado pelo comprador.");

        String pagamentoId = "pagamento-" + id;
        String requestId = "notificacao-" + id;
        mockPagamento(id, pagamentoId, "approved");
        conciliarDepoisDaVerificacaoDoPrazo(id, () -> {
            pagamentos.registrarNotificacao(requestId, pagamentoId);
            pagamentos.registrarNotificacao(requestId, pagamentoId);
            pagamentos.processarNotificacoes();
            assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("reembolso_pendente");
        });
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("reembolso_pendente");
        assertThat(jdbc.queryForObject("SELECT pagamento_id FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo(pagamentoId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_notificacoes WHERE request_id = ? "
                + "AND processado_em IS NOT NULL", Integer.class, requestId)).isEqualTo(1);
        verify(catalogo, never()).liberar(id, compra.reserva());

        pagamentos.processarOperacao(id);
        verify(provedor).reembolsar(pagamentoId, id);
        verify(catalogo, never()).liberar(id, compra.reserva());
        var total = pedidos.consultarInterno(id).total();
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(pagamentoId, id.toString(),
                total, "BRL", "approved", false, "ordem-" + id, new BigDecimal("10.00")));
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("reembolso_pendente");
        verify(catalogo, never()).liberar(id, compra.reserva());
        when(provedor.pagamento(pagamentoId)).thenReturn(new PagamentoGateway.Pagamento(pagamentoId, id.toString(),
                total, "BRL", "approved", false, "ordem-" + id, total));
        pagamentos.aplicarPagamento(pagamentoId);
        mockPagamento(id, pagamentoId, "approved");
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        assertThat(pedidos.consultarInterno(id).motivoEncerramento()).isEqualTo("Cancelado pelo comprador.");
        assertThat(estadoPagamento(id).estado()).isEqualTo("refunded");
        verify(catalogo).liberar(id, compra.reserva());
    }

    @Test
    void cancelamentoObsoletoReconfereVinculoMesmoQuandoEstadoNaoMudou() throws Exception {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        preparar(id);
        pedidos.encerrar(id, compra.comprador(), "cancelado", null);
        String pagamentoId = "pagamento-" + id;
        mockPagamento(id, pagamentoId, "pending");

        conciliarDepoisDaVerificacaoDoPrazo(id, () -> pagamentos.aplicarPagamento(pagamentoId));

        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        assertThat(jdbc.queryForObject("SELECT pagamento_id FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo(pagamentoId);
        pagamentos.processarOperacao(id);
        verify(provedor).cancelar(pagamentoId);
        verify(catalogo, never()).liberar(id, compra.reserva());
    }

    private void conciliarDepoisDaVerificacaoDoPrazo(UUID id, Runnable conciliar) throws Exception {
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '15 minutes' WHERE id = ?", id);
        when(provedor.buscarPagamentos(id.toString())).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of();
        });
        var prazoVerificado = new CountDownLatch(1);
        var conciliacaoAplicada = new CountDownLatch(1);
        doAnswer(chamada -> {
            assertThat(chamada.callRealMethod()).isEqualTo(true);
            prazoVerificado.countDown();
            assertThat(conciliacaoAplicada.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        }).when(pedidoFinanceiro).cancelamentoSemPagamentoExpirou(id);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var cancelamento = executor.submit(() -> pagamentos.processarOperacao(id));
            try {
                assertThat(prazoVerificado.await(10, TimeUnit.SECONDS)).isTrue();
                conciliar.run();
            } finally {
                conciliacaoAplicada.countDown();
            }
            cancelamento.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelamentoEntreReservaECriacaoDaIntencaoConcluiNoMesmoPedido() throws Exception {
        var compra = compraUnica();
        var reservaConcluida = new CompletableFuture<UUID>();
        var retomarCheckout = new CountDownLatch(1);
        doAnswer(chamada -> {
            reservaConcluida.complete(chamada.getArgument(0));
            assertThat(retomarCheckout.await(10, TimeUnit.SECONDS)).isTrue();
            return chamada.callRealMethod();
        }).when(pagamentos).preparar(any());

        UUID id;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var resultado = executor.submit(() -> checkout.checkout(compra.comando(), compra.comprador(), compra.slug(), null));
            try {
                id = reservaConcluida.get(10, TimeUnit.SECONDS);
                assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("aguardando_pagamento");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_intencoes WHERE pedido_id = ?",
                        Integer.class, id)).isZero();
                checkout.cancelar(id, compra.comprador());
            } finally {
                retomarCheckout.countDown();
            }
            assertThat(resultado.get(10, TimeUnit.SECONDS).detalhe().pedido().id()).isEqualTo(id);
        }
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '17 minutes' WHERE id = ?", id);
        pagamentos.reconciliar();
        pedidos.processarPendentes();
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        checkout.cancelar(id, compra.comprador());
        assertThat(pedidos.consultarInterno(id).motivoEncerramento())
                .isEqualTo("Cancelado pelo comprador antes da produção.");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ?",
                String.class, id)).isEqualTo("LIBERADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento_intencoes WHERE pedido_id = ?",
                Integer.class, id)).isZero();
        verify(provedor, never()).criar(eq(id), any(), any());
        verify(provedor, never()).buscarPagamentos(id.toString());
        verify(catalogo).reservar(id, compra.slug());
        verify(catalogo).liberar(id, compra.reserva());
    }

    @Test
    void cancelamentoConvergeQuandoBuscaInicialFalhaAntesDoPost() {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        pedidos.processar(id);
        when(provedor.buscar(id.toString()))
                .thenThrow(new ResourceAccessException("Falha na busca de preferências."));
        pagamentos.preparar(id);
        verify(provedor, never()).criar(eq(id), any(), any());
        pedidos.encerrar(id, compra.comprador(), "cancelado", "Cancelado pelo comprador.");
        org.mockito.Mockito.doReturn(List.of()).when(provedor).buscar(id.toString());
        when(provedor.buscarPagamentos(id.toString())).thenReturn(List.of());

        pagamentos.processarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '17 minutes' WHERE id = ?", id);
        org.mockito.Mockito.doThrow(new ResourceAccessException("Busca ainda indisponível."))
                .when(provedor).buscar(id.toString());
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        verify(catalogo, never()).liberar(id, compra.reserva());

        org.mockito.Mockito.doReturn(List.of()).when(provedor).buscar(id.toString());
        when(provedor.buscarPagamentos(id.toString())).thenThrow(new ResourceAccessException("Busca de pagamentos indisponível."));
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        verify(catalogo, never()).liberar(id, compra.reserva());
        org.mockito.Mockito.doReturn(List.of()).when(provedor).buscarPagamentos(id.toString());
        pagamentos.reconciliar();
        pedidos.processarPendentes();

        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("cancelado");
        assertThat(pedidos.consultarInterno(id).motivoEncerramento()).isEqualTo("Cancelado pelo comprador.");
        assertThat(jdbc.queryForObject("SELECT reserva_estado FROM pedido_itens WHERE pedido_id = ?",
                String.class, id)).isEqualTo("LIBERADA");
        pagamentos.processarOperacao(id);
        verify(provedor, never()).criar(eq(id), any(), any());
        verify(catalogo).reservar(id, compra.slug());
        verify(catalogo).liberar(id, compra.reserva());

        jdbc.update("UPDATE pagamento_intencoes SET atualizado_em = now() - interval '17 minutes' WHERE pedido_id = ?", id);
        org.mockito.Mockito.clearInvocations(provedor);
        pagamentos.reconciliar();
        verify(provedor, never()).buscar(id.toString());
    }

    @Test
    void intencaoCriandoLegadaContinuaAmbiguaAposBuscasVazias() {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        pedidos.processar(id);
        jdbc.update("INSERT INTO pagamento_intencoes(pedido_id, estado, atualizado_em) "
                + "VALUES (?, 'CRIANDO', now() - interval '17 minutes')", id);
        pedidos.encerrar(id, compra.comprador(), "cancelado", null);
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '17 minutes' WHERE id = ?", id);
        when(provedor.buscarPagamentos(id.toString())).thenReturn(List.of());

        pagamentos.reconciliar();
        pedidos.processarPendentes();

        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        assertThat(jdbc.queryForObject("SELECT estado FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo("CRIANDO");
        verify(provedor, never()).criar(eq(id), any(), any());
        verify(catalogo, never()).liberar(id, compra.reserva());
    }

    @Test
    void buscaInicialConcorrenteNaoEnviaPreferenciaDepoisDeCancelar() throws Exception {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        pedidos.processar(id);
        var buscaIniciada = new CountDownLatch(1);
        var retomarBusca = new CountDownLatch(1);
        when(provedor.buscar(id.toString())).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            buscaIniciada.countDown();
            assertThat(retomarBusca.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        }).thenReturn(List.of());
        when(provedor.buscarPagamentos(id.toString())).thenReturn(List.of());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var preparacao = executor.submit(() -> pagamentos.preparar(id));
            try {
                assertThat(buscaIniciada.await(10, TimeUnit.SECONDS)).isTrue();
                pedidos.encerrar(id, compra.comprador(), "cancelado", null);
                jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '17 minutes' WHERE id = ?", id);
                pagamentos.processarOperacao(id);
                assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
            } finally {
                retomarBusca.countDown();
            }
            preparacao.get(10, TimeUnit.SECONDS);
        }
        verify(provedor, never()).criar(eq(id), any(), any());
        verify(catalogo).liberar(id, compra.reserva());
    }

    @Test
    void cancelamentoPreservaIntencaoEmCriacaoAteRecuperarPreferencia() throws Exception {
        var compra = compraUnica();
        UUID id = pedidos.checkout(compra.comando(), compra.comprador(), compra.slug());
        pedidos.processar(id);
        var postIniciado = new CountDownLatch(1);
        var respostaPerdida = new CountDownLatch(1);
        doAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            postIniciado.countDown();
            assertThat(respostaPerdida.await(10, TimeUnit.SECONDS)).isTrue();
            throw new ResourceAccessException("Preferência aceita sem resposta.");
        }).when(provedor).criar(eq(id), any(), any());
        when(provedor.buscarPagamentos(id.toString())).thenReturn(List.of());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var preparacao = executor.submit(() -> pagamentos.preparar(id));
            try {
                assertThat(postIniciado.await(10, TimeUnit.SECONDS)).isTrue();
                pedidos.encerrar(id, compra.comprador(), "cancelado", null);
                jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '17 minutes' WHERE id = ?", id);
                pagamentos.processarOperacao(id);
                assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
                verify(catalogo, never()).liberar(id, compra.reserva());
            } finally {
                respostaPerdida.countDown();
            }
            preparacao.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo("CRIANDO");

        when(provedor.buscar(id.toString())).thenReturn(List.of(new PagamentoGateway.Preferencia(
                "pref-" + id, "https://sandbox.mercadopago.com/checkout/pay?pref_id=pref-" + id)));
        when(provedor.buscarPagamentos(id.toString()))
                .thenThrow(new ResourceAccessException("Busca após recuperação indisponível."))
                .thenReturn(List.of());
        pagamentos.tentarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        assertThat(jdbc.queryForObject("SELECT estado FROM pagamento_intencoes WHERE pedido_id = ?",
                String.class, id)).isEqualTo("PRONTA");
        verify(catalogo, never()).liberar(id, compra.reserva());
        pagamentos.processarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelado");
        verify(provedor).criar(eq(id), any(), any());
        verify(catalogo).reservar(id, compra.slug());
        verify(catalogo).liberar(id, compra.reserva());
    }

    @Test
    void aprovacaoTardiaAposCancelamentoSemPagamentoExigeReembolso() {
        UUID comprador = UUID.randomUUID();
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "tardia-" + UUID.randomUUID();
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, new BigDecimal("55.00"),
                "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", new BigDecimal("55.00"), artesao));
        when(catalogo.liberar(any(), eq(reserva))).thenReturn("LIBERADA");
        UUID id = pedidos.checkout(new Checkout(List.of(new Item(slug, 1)),
                "padrao", "checkout_pro", endereco()), comprador, slug);
        preparar(id);
        pedidos.encerrar(id, comprador, "cancelado", null);
        when(provedor.buscarPagamentos(id.toString())).thenReturn(List.of());
        pagamentos.processarOperacao(id);
        assertThat(pedidos.consultarInterno(id).estado()).isEqualTo("cancelamento_pendente");
        verify(catalogo, never()).liberar(id, reserva);
        jdbc.update("UPDATE pedidos SET atualizado_em = now() - interval '15 minutes' WHERE id = ?", id);
        pagamentos.processarOperacao(id);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");

        String pagamentoId = "pagamento-tardio-" + slug;
        mockPagamento(id, pagamentoId, "approved");
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("reembolso_pendente");
        assertThat(jdbc.queryForObject("SELECT encerramento FROM pedidos WHERE id = ?", String.class, id))
                .isEqualTo("cancelado");
        pagamentos.tentarOperacao(id);
        org.mockito.Mockito.verify(provedor).reembolsar(pagamentoId, id);
        mockPagamento(id, pagamentoId, "refunded");
        pagamentos.aplicarPagamento(pagamentoId);
        assertThat(pedidos.consultar(id, comprador).pedido().estado()).isEqualTo("cancelado");
        assertThat(estadoPagamento(id).estado())
                .isEqualTo("refunded");
    }

    private CompraUnica compraUnica() {
        UUID artesao = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        String slug = "qa-" + UUID.randomUUID();
        var preco = new BigDecimal("55.00");
        when(catalogo.cotar(slug)).thenReturn(new CatalogoCliente.Cotacao(slug, preco, "unica", "atelie", artesao));
        when(catalogo.reservar(any(), eq(slug))).thenReturn(new CatalogoCliente.Reserva(
                reserva, "RESERVADA", preco, artesao));
        when(catalogo.liberar(any(), eq(reserva))).thenAnswer(chamada -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "LIBERADA";
        });
        var comando = new Checkout(List.of(new Item(slug, 1)), "padrao", "checkout_pro", endereco());
        return new CompraUnica(UUID.randomUUID(), reserva, slug, comando);
    }

    private record CompraUnica(UUID comprador, UUID reserva, String slug, Checkout comando) { }

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
