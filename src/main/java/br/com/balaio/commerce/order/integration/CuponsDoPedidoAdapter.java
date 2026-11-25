package br.com.balaio.commerce.order.integration;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import br.com.balaio.commerce.order.application.port.out.CuponsDoPedido;
import br.com.balaio.commerce.promotion.application.port.in.AplicarCupomUseCase;
import br.com.balaio.commerce.promotion.application.port.in.AplicarCupomUseCase.Aplicacao;
import br.com.balaio.commerce.promotion.domain.DescontoCalculado;
import br.com.balaio.commerce.promotion.domain.TipoCupom;

@Component
public class CuponsDoPedidoAdapter implements CuponsDoPedido {
    private final AplicarCupomUseCase cupons;

    public CuponsDoPedidoAdapter(AplicarCupomUseCase cupons) {
        this.cupons = cupons;
    }

    @Override
    public Optional<CupomDoPedido> avaliar(String codigo, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete) {
        return cupons.avaliar(codigo, comprador, subtotalProdutos, frete).map(this::doPedido);
    }

    @Override
    public CupomDoPedido reservar(CupomDoPedido previsto, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete) {
        return doPedido(cupons.reservar(aplicacao(previsto), comprador, subtotalProdutos, frete));
    }

    @Override
    public void registrarUso(CupomDoPedido cupom, UUID pedido, UUID comprador, BigDecimal descontoAplicado) {
        cupons.registrarUso(aplicacao(cupom), pedido, comprador, descontoAplicado);
    }

    private CupomDoPedido doPedido(Aplicacao aplicacao) {
        return new CupomDoPedido(aplicacao.cupomId(), aplicacao.codigo(), aplicacao.descricao(), aplicacao.tipo().codigo(),
                aplicacao.automatico(), aplicacao.desconto().produtos(), aplicacao.desconto().frete(), aplicacao.snapshot());
    }

    private Aplicacao aplicacao(CupomDoPedido cupom) {
        return new Aplicacao(cupom.id(), cupom.codigo(), cupom.descricao(), TipoCupom.de(cupom.tipo()).orElseThrow(),
                cupom.automatico(), new DescontoCalculado(cupom.descontoProdutos(), cupom.descontoFrete()), cupom.snapshot());
    }
}
