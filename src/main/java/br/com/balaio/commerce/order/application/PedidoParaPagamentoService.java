package br.com.balaio.commerce.order.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.order.application.port.out.PedidoFinanceiroRepository;
import br.com.balaio.commerce.order.domain.EstadoPedido;
import org.springframework.stereotype.Service;

@Service
public class PedidoParaPagamentoService implements PedidoParaPagamento {
    private final PedidoFinanceiroRepository repositorio;
    private final ProcessarPedidosPendentesUseCase pedidos;

    public PedidoParaPagamentoService(PedidoFinanceiroRepository repositorio, ProcessarPedidosPendentesUseCase pedidos) {
        this.repositorio = repositorio;
        this.pedidos = pedidos;
    }

    @Override
    public List<ItemCobranca> itensParaCobranca(UUID id) {
        return repositorio.itensParaCobranca(id);
    }

    @Override
    public BigDecimal freteParaCobranca(UUID id) {
        return repositorio.freteParaCobranca(id);
    }

    @Override
    public BigDecimal totalParaCobranca(UUID id) {
        return repositorio.totalParaCobranca(id);
    }

    @Override
    public String estadoParaPagamento(UUID id) {
        return repositorio.estadoParaPagamento(id);
    }

    @Override
    public String travarPedidoParaPagamento(UUID id) {
        return repositorio.travarPedidoParaPagamento(id);
    }

    @Override
    public void aplicarResultadoFinanceiro(UUID id, String estado, Resultado resultado) {
        switch (resultado) {
            case ESTORNADO -> {
                if (EstadoPedido.encerrarAposEstorno(estado))
                    repositorio.mudarEstadoFinanceiro(id, "encerramento_pendente", "reembolsado");
            }
            case APROVADO -> {
                if ("aguardando_pagamento".equals(estado))
                    repositorio.mudarEstadoFinanceiro(id, "confirmacao_pendente", null);
                else if (EstadoPedido.estornarAprovacaoTardia(estado))
                    repositorio.mudarEstadoFinanceiro(id, "reembolso_pendente", "reembolsado");
            }
            case FALHOU -> {
                if (EstadoPedido.encerrarAposFalhaPagamento(estado))
                    repositorio.mudarEstadoFinanceiro(id, "encerramento_pendente",
                            "cancelamento_pendente".equals(estado) ? "cancelado" : "falha_pagamento");
            }
            case INCONCLUSIVO -> { }
        }
    }

    @Override
    public List<UUID> reservasComTentativasEsgotadas() {
        return repositorio.reservasComTentativasEsgotadas();
    }

    @Override
    public boolean compensarReservaFalha(UUID id) {
        return repositorio.mudarEstadoFinanceiroSe(id, "aguardando_reserva", "encerramento_pendente", "falha_reserva");
    }

    @Override
    public List<UUID> confirmacoesComTentativasEsgotadas() {
        return repositorio.confirmacoesComTentativasEsgotadas();
    }

    @Override
    public void reembolsarConfirmacaoFalha(UUID id) {
        repositorio.mudarEstadoFinanceiroSe(id, "confirmacao_pendente", "reembolso_pendente", "reembolsado");
    }

    @Override
    public List<UUID> operacoesFinanceirasPendentes() {
        return repositorio.operacoesFinanceirasPendentes();
    }

    @Override
    public void encerrarPagamentoExpirado(UUID id) {
        repositorio.encerrarPagamentoExpirado(id);
    }

    @Override
    public boolean cancelamentoSemPagamentoExpirou(UUID id) {
        return repositorio.cancelamentoSemPagamentoExpirou(id);
    }

    @Override
    public boolean encerrarCancelamentoSemPagamento(UUID id, boolean exigirPrazo) {
        return repositorio.encerrarCancelamentoSemPagamento(id, exigirPrazo);
    }

    @Override
    public void processar(UUID id) {
        pedidos.processar(id);
    }
}
