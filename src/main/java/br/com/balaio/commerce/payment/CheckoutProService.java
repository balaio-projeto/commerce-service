package br.com.balaio.commerce.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;

import br.com.balaio.commerce.order.PedidoException;
import br.com.balaio.commerce.order.PedidoService;
import tools.jackson.databind.JsonNode;

@Service
public class CheckoutProService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacoes;
    private final CheckoutProClient provedor;
    private final MercadoPagoSandboxConfig config;
    private final PedidoService pedidos;
    private final boolean workerAtivo;

    public CheckoutProService(JdbcTemplate jdbc, TransactionTemplate transacoes, CheckoutProClient provedor,
            MercadoPagoSandboxConfig config, PedidoService pedidos,
            @Value("${payment.mercadopago.worker-enabled:true}") boolean workerAtivo) {
        this.jdbc = jdbc;
        this.transacoes = transacoes;
        this.provedor = provedor;
        this.config = config;
        this.pedidos = pedidos;
        this.workerAtivo = workerAtivo;
    }

    public void preparar(UUID pedidoId) {
        boolean nova = Boolean.TRUE.equals(transacoes.execute(status -> {
            Integer inseridas = jdbc.update("INSERT INTO pagamento_intencoes(pedido_id, estado) "
                    + "SELECT id, 'CRIANDO' FROM pedidos WHERE id = ? AND estado = 'aguardando_pagamento' "
                    + "ON CONFLICT (pedido_id) DO NOTHING", pedidoId);
            return inseridas != null && inseridas > 0;
        }));
        var intencao = intencao(pedidoId);
        if (intencao == null || "PRONTA".equals(intencao.estado())) return;
        try {
            List<CheckoutProClient.Preferencia> encontradas = provedor.buscar(pedidoId.toString());
            if (encontradas.size() > 1) throw new IllegalStateException("Múltiplas preferências para pedido.");
            CheckoutProClient.Preferencia preferencia;
            if (encontradas.size() == 1) preferencia = encontradas.getFirst();
            else if (nova) {
                var itens = jdbc.query("SELECT slug, quantidade, preco FROM pedido_itens WHERE pedido_id = ? ORDER BY slug",
                        (rs, row) -> new CheckoutProClient.Item(rs.getString(1), rs.getInt(2),
                                rs.getBigDecimal(3)), pedidoId);
                BigDecimal frete = jdbc.queryForObject("SELECT frete FROM pedidos WHERE id = ?", BigDecimal.class, pedidoId);
                preferencia = provedor.criar(pedidoId, itens, frete);
            } else return; // POST anterior pode ter sido aceito sem resposta: apenas busca pode recuperá-lo.
            transacoes.executeWithoutResult(status -> jdbc.update(
                    "UPDATE pagamento_intencoes SET estado = 'PRONTA', preferencia_id = ?, checkout_url = ?, erro = NULL, atualizado_em = now() WHERE pedido_id = ? AND estado = 'CRIANDO'",
                    preferencia.id(), preferencia.checkoutUrl(), pedidoId));
        } catch (RuntimeException falha) {
            jdbc.update("UPDATE pagamento_intencoes SET erro = ?, atualizado_em = now() WHERE pedido_id = ?",
                    falha.getClass().getSimpleName(), pedidoId);
        }
    }

    public Map<String, Object> complementar(UUID pedidoId, Map<String, Object> pedido) {
        var dados = new LinkedHashMap<>(pedido);
        var intencao = intencao(pedidoId);
        dados.put("estadoPagamento", intencao == null ? "aguardando_intencao"
                : intencao.statusProvedor() == null ? "aguardando_pagamento" : intencao.statusProvedor());
        dados.put("checkoutUrl", intencao != null && "PRONTA".equals(intencao.estado())
                && "aguardando_pagamento".equals(pedido.get("estado")) ? intencao.checkoutUrl() : null);
        dados.put("simulado", false);
        return dados;
    }

    public void receber(String assinatura, String requestId, String dataId, JsonNode aviso) {
        if (assinatura == null || assinatura.isBlank() || requestId == null || requestId.isBlank()
                || requestId.length() > 160 || dataId == null
                || !dataId.matches("[0-9]{1,80}")) throw new PedidoException(401, "WEBHOOK_INVALIDO", "Notificação inválida.");
        try {
            WebhookSignatureValidator.validate(assinatura, requestId, dataId, config.webhookSecret(),
                    Duration.ofMinutes(10));
        } catch (MPInvalidWebhookSignatureException | IllegalArgumentException invalid) {
            throw new PedidoException(401, "WEBHOOK_INVALIDO", "Notificação inválida.");
        }
        if (aviso == null || !"payment".equals(aviso.path("type").asText())
                || !dataId.equals(aviso.path("data").path("id").asText())
                || aviso.path("live_mode").asBoolean(true))
            throw new PedidoException(400, "WEBHOOK_INVALIDO", "Notificação incompatível.");
        transacoes.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO pagamento_notificacoes(request_id, pagamento_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                requestId, dataId));
    }

    public void processarNotificacoes() {
        var pendentes = jdbc.query("SELECT request_id, pagamento_id FROM pagamento_notificacoes "
                + "WHERE processado_em IS NULL AND proxima_tentativa_em <= now() ORDER BY recebido_em LIMIT 50",
                (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)));
        for (var evento : pendentes) {
            try {
                aplicarPagamento(evento.getValue());
                jdbc.update("UPDATE pagamento_notificacoes SET processado_em = now(), ultimo_erro = NULL "
                        + "WHERE request_id = ? AND pagamento_id = ?", evento.getKey(), evento.getValue());
            } catch (RuntimeException falha) {
                jdbc.update("UPDATE pagamento_notificacoes SET tentativas = tentativas + 1, ultimo_erro = ?, "
                        + "proxima_tentativa_em = now() + make_interval(secs => "
                        + "least(300, power(2, least(tentativas, 8)))::int) "
                        + "WHERE request_id = ? AND pagamento_id = ?",
                        falha.getClass().getSimpleName(), evento.getKey(), evento.getValue());
            }
        }
    }

    public void aplicarPagamento(String pagamentoId) {
        CheckoutProClient.Pagamento pagamento = provedor.pagamento(pagamentoId);
        UUID pedidoId;
        try { pedidoId = UUID.fromString(pagamento.referencia()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Referência de pagamento inválida."); }
        var intencao = intencao(pedidoId);
        if (intencao == null || !"PRONTA".equals(intencao.estado()) || pagamento.liveMode()
                || !"BRL".equals(pagamento.moeda()) || pagamento.ordemId().isBlank())
            throw new IllegalStateException("Pagamento não pertence a uma intenção sandbox pronta.");
        CheckoutProClient.Ordem ordem = provedor.ordem(pagamento.ordemId());
        if (!intencao.preferenciaId().equals(ordem.preferenciaId())
                || !pedidoId.toString().equals(ordem.referencia()))
            throw new IllegalStateException("Preferência de pagamento divergente.");
        BigDecimal total = jdbc.queryForObject("SELECT total FROM pedidos WHERE id = ?", BigDecimal.class, pedidoId);
        if (total == null || pagamento.valor().compareTo(total) != 0)
            throw new IllegalStateException("Valor de pagamento divergente.");
        transacoes.executeWithoutResult(status -> {
            String estado = jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ? FOR UPDATE", String.class, pedidoId);
            var anterior = jdbc.query("SELECT pagamento_id, status_provedor FROM pagamento_intencoes "
                    + "WHERE pedido_id = ? FOR UPDATE", (rs, row) -> Map.entry(
                    rs.getString(1) == null ? "" : rs.getString(1),
                    rs.getString(2) == null ? "" : rs.getString(2)), pedidoId).getFirst();
            if (!anterior.getKey().isBlank() && !anterior.getKey().equals(pagamentoId)
                    && !List.of("rejected", "cancelled", "expired").contains(anterior.getValue()))
                throw new IllegalStateException("Outra cobrança já vinculada ao pedido.");
            jdbc.update("UPDATE pagamento_intencoes SET pagamento_id = ?, status_provedor = ?, atualizado_em = now() "
                    + "WHERE pedido_id = ?", pagamentoId, pagamento.status(), pedidoId);
            switch (pagamento.status()) {
                case "approved" -> {
                    if (pagamento.valorReembolsado().compareTo(total) >= 0) {
                        if (!"reembolsado".equals(estado))
                            mudar(pedidoId, "encerramento_pendente", "reembolsado");
                        break;
                    }
                    if (pagamento.valorReembolsado().signum() != 0) break;
                    if ("aguardando_pagamento".equals(estado)) mudar(pedidoId, "confirmacao_pendente", null);
                    else if (List.of("cancelamento_pendente", "encerramento_pendente", "falha_pagamento",
                            "cancelado", "recusado").contains(estado))
                        mudar(pedidoId, "reembolso_pendente", "reembolsado");
                }
                case "rejected", "cancelled", "expired" -> {
                    if ("aguardando_pagamento".equals(estado) || "cancelamento_pendente".equals(estado))
                        mudar(pedidoId, "encerramento_pendente",
                                "cancelamento_pendente".equals(estado) ? "cancelado" : "falha_pagamento");
                }
                case "refunded" -> {
                    if (!"reembolsado".equals(estado)) mudar(pedidoId, "encerramento_pendente", "reembolsado");
                }
                case "pending", "in_process", "authorized", "in_mediation", "charged_back" -> { }
                default -> { } // Estado desconhecido exige reconciliação; não confirma nem libera.
            }
        });
        pedidos.processar(pedidoId);
    }

    @Scheduled(fixedDelayString = "${payment.mercadopago.worker-interval-ms:10000}")
    public void reconciliar() {
        if (!workerAtivo) return;
        processarNotificacoes();
        var reservasFalhas = jdbc.query("SELECT id FROM pedidos WHERE estado = 'aguardando_reserva' "
                + "AND tentativas >= 10 LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
        for (UUID id : reservasFalhas) {
            if (mudarSe(id, "aguardando_reserva", "encerramento_pendente", "falha_reserva"))
                pedidos.processar(id);
        }
        var pendentes = jdbc.query("SELECT pedido_id FROM pagamento_intencoes WHERE estado = 'CRIANDO' "
                + "AND atualizado_em < now() - interval '10 seconds' LIMIT 30",
                (rs, row) -> (UUID) rs.getObject(1));
        for (UUID id : pendentes) preparar(id);
        var pagamentos = jdbc.query("SELECT p.id, i.pagamento_id FROM pedidos p JOIN pagamento_intencoes i "
                + "ON i.pedido_id = p.id WHERE i.pagamento_id IS NOT NULL "
                + "AND p.estado IN ('aguardando_pagamento', 'confirmacao_pendente', 'cancelamento_pendente', 'reembolso_pendente') "
                + "AND i.atualizado_em < now() - interval '30 seconds' LIMIT 30",
                (rs, row) -> Map.entry((UUID) rs.getObject(1), rs.getString(2)));
        for (var pagamento : pagamentos) {
            try { aplicarPagamento(pagamento.getValue()); }
            catch (RuntimeException falha) { registrarErro(pagamento.getKey(), falha); }
        }
        var expirados = jdbc.query("SELECT p.id FROM pedidos p JOIN pagamento_intencoes i ON i.pedido_id = p.id "
                + "WHERE p.estado = 'aguardando_pagamento' AND i.criado_em < now() - interval '16 minutes' LIMIT 30",
                (rs, row) -> (UUID) rs.getObject(1));
        for (UUID id : expirados) {
            try {
                var encontrados = provedor.buscarPagamentos(id.toString());
                for (String pagamento : encontrados) aplicarPagamento(pagamento);
                if (encontrados.isEmpty()) {
                    transacoes.executeWithoutResult(status -> jdbc.update("UPDATE pedidos SET estado = 'encerramento_pendente', "
                            + "encerramento = 'falha_pagamento', atualizado_em = now() WHERE id = ? "
                            + "AND estado = 'aguardando_pagamento'", id));
                    pedidos.processar(id);
                }
            } catch (RuntimeException falha) { registrarErro(id, falha); }
        }
        var confirmacoesFalhas = jdbc.query("SELECT id FROM pedidos WHERE estado = 'confirmacao_pendente' "
                + "AND tentativas >= 10 LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
        for (UUID id : confirmacoesFalhas) {
            mudarSe(id, "confirmacao_pendente", "reembolso_pendente", "reembolsado");
        }
        var operacoes = jdbc.query("SELECT id FROM pedidos WHERE estado IN ('cancelamento_pendente', 'reembolso_pendente') "
                + "LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
        for (UUID id : operacoes) {
            try { processarOperacao(id); }
            catch (RuntimeException falha) { registrarErro(id, falha); }
        }
    }

    public void processarOperacao(UUID id) {
        String estado = jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id);
        var intencao = intencao(id);
        if (intencao == null || !List.of("cancelamento_pendente", "reembolso_pendente").contains(estado)) return;
        String pagamentoId = intencao.pagamentoId();
        if (pagamentoId == null) {
            var encontrados = provedor.buscarPagamentos(id.toString());
            for (String encontrado : encontrados) aplicarPagamento(encontrado);
            if (!encontrados.isEmpty()) return;
            if ("cancelamento_pendente".equals(estado) && jdbc.queryForObject(
                    "SELECT atualizado_em < now() - interval '14 minutes' FROM pedidos WHERE id = ?",
                    Boolean.class, id)) {
                mudar(id, "encerramento_pendente", "cancelado");
                pedidos.processar(id);
            }
            return;
        }
        aplicarPagamento(pagamentoId);
        var pagamento = provedor.pagamento(pagamentoId);
        estado = jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id);
        if ("cancelamento_pendente".equals(estado)
                && List.of("pending", "in_process", "authorized").contains(pagamento.status())) {
            provedor.cancelar(pagamentoId);
            aplicarPagamento(pagamentoId);
        } else if ("reembolso_pendente".equals(estado) && "approved".equals(pagamento.status())) {
            provedor.reembolsar(pagamentoId, id);
            aplicarPagamento(pagamentoId);
        }
    }

    public void tentarOperacao(UUID id) {
        try { processarOperacao(id); }
        catch (RuntimeException falha) { registrarErro(id, falha); }
    }

    private void mudar(UUID id, String estado, String encerramento) {
        jdbc.update("UPDATE pedidos SET estado = ?, encerramento = COALESCE(?, encerramento), atualizado_em = now() "
                + "WHERE id = ?", estado, encerramento, id);
    }

    private boolean mudarSe(UUID id, String anterior, String destino, String encerramento) {
        return jdbc.update("UPDATE pedidos SET estado = ?, encerramento = COALESCE(?, encerramento), "
                + "atualizado_em = now() WHERE id = ? AND estado = ?", destino, encerramento, id, anterior) == 1;
    }

    private void registrarErro(UUID id, RuntimeException falha) {
        jdbc.update("UPDATE pagamento_intencoes SET erro = ?, atualizado_em = now() WHERE pedido_id = ?",
                falha.getClass().getSimpleName(), id);
    }

    private Intencao intencao(UUID id) {
        return jdbc.query("SELECT estado, preferencia_id, checkout_url, pagamento_id, status_provedor "
                + "FROM pagamento_intencoes WHERE pedido_id = ?", (rs, row) -> new Intencao(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id)
                .stream().findFirst().orElse(null);
    }

    private record Intencao(String estado, String preferenciaId, String checkoutUrl, String pagamentoId,
                            String statusProvedor) { }
}
