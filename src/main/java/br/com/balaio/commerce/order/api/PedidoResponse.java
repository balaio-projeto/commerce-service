package br.com.balaio.commerce.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase.DetalheComPagamento;
import br.com.balaio.commerce.checkout.application.port.in.CheckoutUseCase.ResumoComPagamento;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Etapa;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.ItemResumo;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Pendente;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.payment.application.port.in.PagamentoCheckoutUseCase.EstadoPagamento;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record PedidoResponse(UUID id, UUID compradorId, UUID artesaoId, String pecaSlug, String compradorNome,
        String data, String estado, BigDecimal total, boolean avaliado, boolean simulado, String motivoEncerramento,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean historicoInferido,
        List<EtapaResponse> etapas, List<ItemResponse> itens) {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static PedidoResponse de(Resumo resumo) {
        return new PedidoResponse(resumo.id(), resumo.compradorId(), resumo.artesaoId(), resumo.pecaSlug(), "Comprador",
                resumo.criadoEm().format(DATA), resumo.estado(), resumo.total(), false, false, resumo.motivoEncerramento(),
                resumo.historicoInferido() ? Boolean.TRUE : null,
                resumo.etapas().stream().map(PedidoResponse::etapa).toList(),
                resumo.itens().stream().map(ItemResponse::de).toList());
    }

    public static ComPagamento comPagamento(ResumoComPagamento resumo) {
        return comPagamento(resumo.pedido(), resumo.pagamento());
    }

    public static ComEndereco detalhe(DetalheComPagamento detalhe) {
        return new ComEndereco(comPagamento(detalhe.detalhe().pedido(), detalhe.pagamento()), detalhe.detalhe().endereco());
    }

    private static ComPagamento comPagamento(Resumo resumo, EstadoPagamento pagamento) {
        return new ComPagamento(de(resumo), pagamento.estado(), pagamento.checkoutUrl());
    }

    private static EtapaResponse etapa(Etapa etapa) {
        return etapa.inferida()
                ? new EtapaInferida(etapa.estado(), etapa.titulo(), etapa.detalhe(), etapa.concluida(), etapa.atual())
                : new EtapaRegistrada(etapa.estado(), etapa.titulo(), etapa.detalhe(), etapa.nota(),
                        etapa.criadaEm(), etapa.concluida(), etapa.atual());
    }

    public record ComPagamento(@JsonUnwrapped PedidoResponse pedido, String estadoPagamento, String checkoutUrl) { }
    public record ComEndereco(@JsonUnwrapped ComPagamento pedido, Object endereco) { }

    public record PendenteResponse(UUID id, String pecaSlug, String comprador, String quando, BigDecimal valor) {
        public static PendenteResponse de(Pendente pendente) {
            return new PendenteResponse(pendente.id(), pendente.pecaSlug(), "Comprador",
                    pendente.criadoEm().format(DATA), pendente.valor());
        }
    }

    public record ItemResponse(String slug, int quantidade, BigDecimal preco) {
        private static ItemResponse de(ItemResumo item) {
            return new ItemResponse(item.slug(), item.quantidade(), item.preco());
        }
    }

    public sealed interface EtapaResponse permits EtapaRegistrada, EtapaInferida { }
    public record EtapaRegistrada(String estado, String titulo, String detalhe, String nota, OffsetDateTime criadaEm,
            boolean concluida, boolean atual) implements EtapaResponse { }
    public record EtapaInferida(String estado, String titulo, String detalhe,
            boolean concluida, boolean atual) implements EtapaResponse { }
}
