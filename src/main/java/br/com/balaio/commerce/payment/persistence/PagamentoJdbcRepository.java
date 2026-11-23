package br.com.balaio.commerce.payment.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.payment.application.port.out.PagamentoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PagamentoJdbcRepository implements PagamentoRepository {
    private final JdbcTemplate jdbc;

    public PagamentoJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean criarIntencaoSeElegivel(UUID pedido) {
        return jdbc.update("INSERT INTO pagamento_intencoes(pedido_id, estado) "
                + "SELECT id, 'SEM_ENVIO' FROM pedidos WHERE id = ? AND estado = 'aguardando_pagamento' FOR UPDATE "
                + "ON CONFLICT (pedido_id) DO NOTHING", pedido) > 0;
    }

    @Override
    public Intencao buscarIntencao(UUID pedido) {
        return jdbc.query("SELECT estado, preferencia_id, checkout_url, pagamento_id, status_provedor, valor_reembolsado "
                + "FROM pagamento_intencoes WHERE pedido_id = ?", (rs, row) -> new Intencao(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBigDecimal(6)), pedido).stream().findFirst().orElse(null);
    }

    @Override
    public Optional<IntencaoTravada> travarIntencao(UUID pedido) {
        return jdbc.query("SELECT estado, pagamento_id, status_provedor, valor_reembolsado FROM pagamento_intencoes "
                + "WHERE pedido_id = ? FOR UPDATE", (rs, row) -> new IntencaoTravada(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)), pedido)
                .stream().findFirst();
    }

    @Override
    public boolean marcarInicioEnvioPreferencia(UUID pedido) {
        return jdbc.update("UPDATE pagamento_intencoes SET estado = 'CRIANDO', atualizado_em = now() "
                + "WHERE pedido_id = ? AND estado = 'SEM_ENVIO'", pedido) == 1;
    }

    @Override
    public void registrarPreferencia(UUID pedido, String preferenciaId, String checkoutUrl) {
        jdbc.update("UPDATE pagamento_intencoes SET estado = 'PRONTA', preferencia_id = ?, checkout_url = ?, "
                + "erro = NULL, atualizado_em = now() WHERE pedido_id = ? AND estado IN ('SEM_ENVIO', 'CRIANDO')",
                preferenciaId, checkoutUrl, pedido);
    }

    @Override
    public void registrarResultado(UUID pedido, String pagamentoId, String status, BigDecimal valorReembolsado) {
        jdbc.update("UPDATE pagamento_intencoes SET pagamento_id = ?, status_provedor = ?, "
                + "valor_reembolsado = ?, atualizado_em = now() WHERE pedido_id = ?",
                pagamentoId, status, valorReembolsado, pedido);
    }

    @Override
    public void registrarErro(UUID pedido, String diagnostico) {
        jdbc.update("UPDATE pagamento_intencoes SET erro = ?, atualizado_em = now() WHERE pedido_id = ?",
                diagnostico, pedido);
    }

    @Override
    public List<UUID> intencoesPendentes() {
        return jdbc.query("""
                SELECT i.pedido_id FROM pagamento_intencoes i JOIN pedidos p ON p.id = i.pedido_id
                WHERE (i.estado = 'CRIANDO' OR (i.estado = 'SEM_ENVIO' AND p.estado = 'aguardando_pagamento'))
                  AND i.atualizado_em < now() - interval '10 seconds' LIMIT 30
                """, (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public List<PagamentoPendente> pagamentosPendentes() {
        return jdbc.query("SELECT p.id, i.pagamento_id FROM pedidos p JOIN pagamento_intencoes i "
                + "ON i.pedido_id = p.id WHERE i.pagamento_id IS NOT NULL "
                + "AND p.estado IN ('aguardando_pagamento', 'confirmacao_pendente', 'cancelamento_pendente', 'reembolso_pendente') "
                + "AND i.atualizado_em < now() - interval '30 seconds' LIMIT 30",
                (rs, row) -> new PagamentoPendente((UUID) rs.getObject(1), rs.getString(2)));
    }

    @Override
    public List<UUID> pagamentosExpirados() {
        return jdbc.query("SELECT p.id FROM pedidos p JOIN pagamento_intencoes i ON i.pedido_id = p.id "
                + "WHERE p.estado = 'aguardando_pagamento' AND i.criado_em < now() - interval '16 minutes' LIMIT 30",
                (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public void registrarNotificacao(String requestId, String pagamentoId) {
        jdbc.update("INSERT INTO pagamento_notificacoes(request_id, pagamento_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                requestId, pagamentoId);
    }

    @Override
    public List<Notificacao> notificacoesPendentes() {
        return jdbc.query("SELECT request_id, pagamento_id FROM pagamento_notificacoes "
                + "WHERE processado_em IS NULL AND proxima_tentativa_em <= now() ORDER BY recebido_em LIMIT 50",
                (rs, row) -> new Notificacao(rs.getString(1), rs.getString(2)));
    }

    @Override
    public void concluirNotificacao(Notificacao notificacao) {
        jdbc.update("UPDATE pagamento_notificacoes SET processado_em = now(), ultimo_erro = NULL "
                + "WHERE request_id = ? AND pagamento_id = ?", notificacao.requestId(), notificacao.pagamentoId());
    }

    @Override
    public void reagendarNotificacao(Notificacao notificacao, String diagnostico) {
        jdbc.update("UPDATE pagamento_notificacoes SET tentativas = tentativas + 1, ultimo_erro = ?, "
                + "proxima_tentativa_em = now() + make_interval(secs => "
                + "least(300, power(2, least(tentativas, 8)))::int) WHERE request_id = ? AND pagamento_id = ?",
                diagnostico, notificacao.requestId(), notificacao.pagamentoId());
    }
}
