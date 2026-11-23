package br.com.balaio.commerce.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PedidoParaPagamento {
    List<ItemCobranca> itensParaCobranca(UUID id);
    BigDecimal freteParaCobranca(UUID id);
    BigDecimal totalParaCobranca(UUID id);
    String estadoParaPagamento(UUID id);
    String travarPedidoParaPagamento(UUID id);
    void aplicarResultadoFinanceiro(UUID id, String estado, Resultado resultado);
    List<UUID> reservasComTentativasEsgotadas();
    boolean compensarReservaFalha(UUID id);
    List<UUID> confirmacoesComTentativasEsgotadas();
    void reembolsarConfirmacaoFalha(UUID id);
    List<UUID> operacoesFinanceirasPendentes();
    void encerrarPagamentoExpirado(UUID id);
    boolean cancelamentoSemPagamentoExpirou(UUID id);
    boolean encerrarCancelamentoSemPagamento(UUID id, boolean exigirPrazo);
    void processar(UUID id);

    record ItemCobranca(String slug, int quantidade, BigDecimal preco) { }
    enum Resultado { ESTORNADO, APROVADO, FALHOU, INCONCLUSIVO }
}
