package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento.ItemCobranca;

public interface PedidoFinanceiroRepository {
    List<UUID> reservasComTentativasEsgotadas();
    List<UUID> confirmacoesComTentativasEsgotadas();
    List<UUID> operacoesFinanceirasPendentes();
    List<ItemCobranca> itensParaCobranca(UUID id);
    BigDecimal freteParaCobranca(UUID id);
    BigDecimal totalParaCobranca(UUID id);
    String estadoParaPagamento(UUID id);
    String travarPedidoParaPagamento(UUID id);
    boolean cancelamentoSemPagamentoExpirou(UUID id);
    boolean encerrarCancelamentoSemPagamento(UUID id, boolean exigirPrazo);
    void mudarEstadoFinanceiro(UUID id, String estado, String causa);
    boolean mudarEstadoFinanceiroSe(UUID id, String anterior, String destino, String causa);
    void encerrarPagamentoExpirado(UUID id);
}
