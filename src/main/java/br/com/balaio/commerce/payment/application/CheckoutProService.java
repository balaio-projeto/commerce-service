package br.com.balaio.commerce.payment.application;

import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento;
import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento.Resultado;
import br.com.balaio.commerce.payment.application.port.in.RegistrarNotificacaoUseCase;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase;
import br.com.balaio.commerce.payment.application.port.in.ReconciliarPagamentosUseCase;
import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;
import br.com.balaio.commerce.payment.application.port.out.PagamentoRepository;
import br.com.balaio.commerce.payment.application.port.out.AmbientePagamento;
import br.com.balaio.commerce.payment.domain.ResultadoFinanceiro;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class CheckoutProService implements RegistrarNotificacaoUseCase, PagamentoCheckoutUseCase, ReconciliarPagamentosUseCase {
    private final PagamentoRepository repositorio;
    private final TransactionTemplate transacoes;
    private final PagamentoGateway provedor;
    private final AmbientePagamento config;
    private final PedidoParaPagamento pedidos;

    public CheckoutProService(PagamentoRepository repositorio, TransactionTemplate transacoes, PagamentoGateway provedor,
            AmbientePagamento config, PedidoParaPagamento pedidos) {
        this.repositorio = repositorio;
        this.transacoes = transacoes;
        this.provedor = provedor;
        this.config = config;
        this.pedidos = pedidos;
    }

    @Override
    public void preparar(UUID pedidoId) {
        if (!config.enabled()) return;
        boolean nova = Boolean.TRUE.equals(transacoes.execute(status -> repositorio.criarIntencaoSeElegivel(pedidoId)));
        var intencao = repositorio.buscarIntencao(pedidoId);
        if (intencao == null || "PRONTA".equals(intencao.estado())) return;
        try {
            resolverPreferencia(pedidoId, nova);
        } catch (RuntimeException falha) {
            registrarErro(pedidoId, falha);
        }
    }

    private void resolverPreferencia(UUID pedidoId, boolean primeiraPreparacao) {
        List<PagamentoGateway.Preferencia> encontradas = provedor.buscar(pedidoId.toString());
        if (encontradas.size() > 1) throw new IllegalStateException("Múltiplas preferências para pedido.");
        PagamentoGateway.Preferencia preferencia;
        if (encontradas.size() == 1) {
            preferencia = encontradas.getFirst();
        } else if (primeiraPreparacao) {
            var itens = pedidos.itensParaCobranca(pedidoId).stream()
                    .map(item -> new PagamentoGateway.Item(item.slug(), item.quantidade(), item.preco())).toList();
            BigDecimal frete = pedidos.freteParaCobranca(pedidoId);
            // Se houver reinício após este commit, o resultado do POST deve ser tratado como ambíguo.
            boolean envioIniciado = Boolean.TRUE.equals(transacoes.execute(status ->
                    "aguardando_pagamento".equals(pedidos.travarPedidoParaPagamento(pedidoId))
                            && repositorio.marcarInicioEnvioPreferencia(pedidoId)));
            if (!envioIniciado) return;
            preferencia = provedor.criar(pedidoId, itens, frete);
        } else {
            return;
        }
        transacoes.executeWithoutResult(status -> repositorio.registrarPreferencia(
                pedidoId, preferencia.id(), preferencia.checkoutUrl()));
    }

    @Override
    public void exigirDisponivel() {
        if (!config.enabled())
            throw new PagamentoIndisponivel();
    }

    @Override
    public EstadoPagamento consultarPagamento(UUID pedidoId, String estadoPedido, BigDecimal total) {
        var intencao = repositorio.buscarIntencao(pedidoId);
        if (intencao == null) return new EstadoPagamento("aguardando_intencao", null);
        String estado = "refunded".equals(intencao.statusProvedor())
                || (total != null && intencao.valorReembolsado().compareTo(total) >= 0) ? "refunded"
                : intencao.statusProvedor() == null ? "aguardando_pagamento" : intencao.statusProvedor();
        String url = "PRONTA".equals(intencao.estado()) && "aguardando_pagamento".equals(estadoPedido)
                ? intencao.checkoutUrl() : null;
        return new EstadoPagamento(estado, url);
    }

    @Override
    public void registrarNotificacao(String requestId, String pagamentoId) {
        transacoes.executeWithoutResult(status -> repositorio.registrarNotificacao(requestId, pagamentoId));
    }

    public void processarNotificacoes() {
        for (var evento : repositorio.notificacoesPendentes()) {
            try {
                aplicarPagamento(evento.pagamentoId());
                repositorio.concluirNotificacao(evento);
            } catch (RuntimeException falha) {
                repositorio.reagendarNotificacao(evento, diagnostico(falha));
            }
        }
    }

    public void aplicarPagamento(String pagamentoId) {
        PagamentoGateway.Pagamento pagamento = provedor.pagamento(pagamentoId);
        UUID pedidoId;
        try { pedidoId = UUID.fromString(pagamento.referencia()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Referência de pagamento inválida."); }
        var intencao = repositorio.buscarIntencao(pedidoId);
        if (intencao == null || !"PRONTA".equals(intencao.estado()) || pagamento.liveMode() != config.producao()
                || !"BRL".equals(pagamento.moeda()) || pagamento.ordemId().isBlank())
            throw new IllegalStateException("Pagamento não pertence a uma intenção pronta deste ambiente.");
        PagamentoGateway.Ordem ordem = provedor.ordem(pagamento.ordemId());
        if (!intencao.preferenciaId().equals(ordem.preferenciaId())
                || !pedidoId.toString().equals(ordem.referencia()))
            throw new IllegalStateException("Preferência de pagamento divergente.");
        BigDecimal total = pedidos.totalParaCobranca(pedidoId);
        if (total == null || pagamento.valor().compareTo(total) != 0)
            throw new IllegalStateException("Valor de pagamento divergente.");
        transacoes.executeWithoutResult(status -> {
            // Pedido antes da intenção, na mesma transação que grava o resultado e a etapa do pedido.
            String estado = pedidos.travarPedidoParaPagamento(pedidoId);
            var anterior = repositorio.travarIntencao(pedidoId)
                    .orElseThrow(() -> new IllegalStateException("Intenção de pagamento não encontrada."));
            var resultado = ResultadoFinanceiro.conciliar(anterior.pagamentoId(), anterior.statusProvedor(),
                    anterior.valorReembolsado(), pagamentoId, pagamento.status(), pagamento.valorReembolsado(), total);
            repositorio.registrarResultado(pedidoId, pagamentoId, resultado.status(), resultado.valorReembolsado());
            pedidos.aplicarResultadoFinanceiro(pedidoId, estado, classificar(pagamento.status(), resultado));
        });
        pedidos.processar(pedidoId);
    }

    private Resultado classificar(String status, ResultadoFinanceiro resultado) {
        if (resultado.estornoConfirmado()) return Resultado.ESTORNADO;
        if ("approved".equals(status) && resultado.valorReembolsado().signum() == 0) return Resultado.APROVADO;
        if (ResultadoFinanceiro.falhou(status)) return Resultado.FALHOU;
        return Resultado.INCONCLUSIVO;
    }

    @Override
    public void reconciliar() {
        if (!config.enabled()) return;
        processarNotificacoes();
        encerrarReservasFalhas();
        recuperarIntencoesPendentes();
        atualizarPagamentosPendentes();
        encerrarPagamentosExpirados();
        reembolsarConfirmacoesFalhas();
        processarOperacoesPendentes();
    }

    private void encerrarReservasFalhas() {
        for (UUID id : pedidos.reservasComTentativasEsgotadas()) {
            if (pedidos.compensarReservaFalha(id)) pedidos.processar(id);
        }
    }

    private void recuperarIntencoesPendentes() {
        repositorio.intencoesPendentes().forEach(this::preparar);
    }

    private void atualizarPagamentosPendentes() {
        for (var pagamento : repositorio.pagamentosPendentes()) {
            try { aplicarPagamento(pagamento.pagamentoId()); }
            catch (RuntimeException falha) { registrarErro(pagamento.pedidoId(), falha); }
        }
    }

    private void encerrarPagamentosExpirados() {
        for (UUID id : repositorio.pagamentosExpirados()) {
            try {
                var encontrados = provedor.buscarPagamentos(id.toString());
                for (String pagamento : encontrados) aplicarPagamento(pagamento);
                if (encontrados.isEmpty()) {
                    transacoes.executeWithoutResult(status -> pedidos.encerrarPagamentoExpirado(id));
                    pedidos.processar(id);
                }
            } catch (RuntimeException falha) { registrarErro(id, falha); }
        }
    }

    private void reembolsarConfirmacoesFalhas() {
        pedidos.confirmacoesComTentativasEsgotadas().forEach(pedidos::reembolsarConfirmacaoFalha);
    }

    private void processarOperacoesPendentes() {
        pedidos.operacoesFinanceirasPendentes().forEach(this::tentarOperacao);
    }

    public void processarOperacao(UUID id) {
        String estado = pedidos.estadoParaPagamento(id);
        boolean cancelando = "cancelamento_pendente".equals(estado);
        if (!cancelando && !"reembolso_pendente".equals(estado)) return;
        var intencao = repositorio.buscarIntencao(id);
        if (intencao == null) {
            if (cancelando) encerrarCancelamentoSemPagamento(id, false);
            return;
        }
        if ("CRIANDO".equals(intencao.estado()) || "SEM_ENVIO".equals(intencao.estado())) {
            resolverPreferencia(id, false);
            intencao = repositorio.buscarIntencao(id);
            if (intencao == null || !("PRONTA".equals(intencao.estado())
                    || (cancelando && "SEM_ENVIO".equals(intencao.estado())))) return;
        }
        String pagamentoId = intencao.pagamentoId();
        if (pagamentoId == null) {
            var encontrados = provedor.buscarPagamentos(id.toString());
            for (String encontrado : encontrados) aplicarPagamento(encontrado);
            if (cancelando && encontrados.isEmpty() && pedidos.cancelamentoSemPagamentoExpirou(id)) {
                encerrarCancelamentoSemPagamento(id, true);
            }
            return;
        }
        aplicarPagamento(pagamentoId);
        var pagamento = provedor.pagamento(pagamentoId);
        estado = pedidos.estadoParaPagamento(id);
        if ("cancelamento_pendente".equals(estado)
                && List.of("pending", "in_process", "authorized").contains(pagamento.status())) {
            provedor.cancelar(pagamentoId);
            aplicarPagamento(pagamentoId);
        } else if ("reembolso_pendente".equals(estado) && "approved".equals(pagamento.status())) {
            provedor.reembolsar(pagamentoId, id);
            aplicarPagamento(pagamentoId);
        }
    }

    private void encerrarCancelamentoSemPagamento(UUID id, boolean buscaVazia) {
        boolean encerrou = Boolean.TRUE.equals(transacoes.execute(status -> {
            // A criação da intenção e a conciliação também travam o pedido primeiro.
            if (!"cancelamento_pendente".equals(pedidos.travarPedidoParaPagamento(id))) return false;
            var intencao = repositorio.travarIntencao(id);
            boolean podeEncerrar = buscaVazia
                    ? intencao.filter(atual -> atual.pagamentoId() == null
                            && ("PRONTA".equals(atual.estado()) || "SEM_ENVIO".equals(atual.estado()))).isPresent()
                    : intencao.isEmpty();
            return podeEncerrar && pedidos.encerrarCancelamentoSemPagamento(id, buscaVazia);
        }));
        if (encerrou) pedidos.processar(id);
    }

    @Override
    public void tentarOperacao(UUID id) {
        try { processarOperacao(id); }
        catch (RuntimeException falha) { registrarErro(id, falha); }
    }

    private String diagnostico(RuntimeException falha) {
        return falha instanceof PagamentoGateway.ProvedorIndisponivel provedor
                ? provedor.diagnostico() : falha.getClass().getSimpleName();
    }

    private void registrarErro(UUID id, RuntimeException falha) {
        repositorio.registrarErro(id, diagnostico(falha));
    }
}
