package br.com.balaio.commerce.payment.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PagamentoRepository {
    boolean criarIntencaoSeElegivel(UUID pedido);
    Intencao buscarIntencao(UUID pedido);
    Optional<IntencaoTravada> travarIntencao(UUID pedido);
    boolean marcarInicioEnvioPreferencia(UUID pedido);
    void registrarPreferencia(UUID pedido, String preferenciaId, String checkoutUrl);
    void registrarResultado(UUID pedido, String pagamentoId, String status, BigDecimal valorReembolsado);
    void registrarErro(UUID pedido, String diagnostico);
    List<UUID> intencoesPendentes();
    List<PagamentoPendente> pagamentosPendentes();
    List<UUID> pagamentosExpirados();

    void registrarNotificacao(String requestId, String pagamentoId);
    List<Notificacao> notificacoesPendentes();
    void concluirNotificacao(Notificacao notificacao);
    void reagendarNotificacao(Notificacao notificacao, String diagnostico);

    record Intencao(String estado, String preferenciaId, String checkoutUrl, String pagamentoId,
            String statusProvedor, BigDecimal valorReembolsado) { }
    record IntencaoTravada(String estado, String pagamentoId, String statusProvedor, BigDecimal valorReembolsado) { }
    record PagamentoPendente(UUID pedidoId, String pagamentoId) { }
    record Notificacao(String requestId, String pagamentoId) { }
}
