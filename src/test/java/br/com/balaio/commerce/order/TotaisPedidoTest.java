package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.order.application.PedidoParaPagamentoService;
import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento.ItemCobranca;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.order.application.port.out.PedidoFinanceiroRepository;
import br.com.balaio.commerce.order.application.port.out.PedidoFinanceiroRepository.DescontoCobranca;
import br.com.balaio.commerce.order.domain.TotaisPedido;

class TotaisPedidoTest {
    @Test
    void descontoNuncaDeixaOPedidoAbaixoDoMinimoCobrado() {
        var integral = TotaisPedido.limitar(new BigDecimal("80.00"), new BigDecimal("0.00"), new BigDecimal("80.00"),
                BigDecimal.ZERO);
        var comFrete = TotaisPedido.limitar(new BigDecimal("80.00"), new BigDecimal("20.00"), new BigDecimal("80.00"),
                BigDecimal.ZERO);
        var freteGratis = TotaisPedido.limitar(new BigDecimal("80.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                new BigDecimal("25.00"));

        assertThat(integral.produtos()).isEqualByComparingTo("79.00");
        assertThat(TotaisPedido.total(new BigDecimal("80.00"), BigDecimal.ZERO, integral)).isEqualByComparingTo("1.00");
        assertThat(comFrete.produtos()).isEqualByComparingTo("80.00");
        assertThat(TotaisPedido.total(new BigDecimal("80.00"), new BigDecimal("20.00"), comFrete)).isEqualByComparingTo("20.00");
        assertThat(freteGratis.frete()).isEqualByComparingTo("20.00");
        assertThat(TotaisPedido.total(new BigDecimal("80.00"), new BigDecimal("20.00"), freteGratis))
                .isEqualByComparingTo("80.00");
    }

    @Test
    void cobrancaComDescontoSomaExatamenteOTotalDoPedido() {
        var repositorio = mock(PedidoFinanceiroRepository.class);
        var servico = new PedidoParaPagamentoService(repositorio, mock(ProcessarPedidosPendentesUseCase.class));
        UUID pedido = UUID.randomUUID();
        when(repositorio.itensParaCobranca(pedido)).thenReturn(List.of(
                new ItemCobranca("vaso", 2, new BigDecimal("33.33")), new ItemCobranca("cesto", 1, new BigDecimal("50.00"))));
        when(repositorio.freteParaCobranca(pedido)).thenReturn(new BigDecimal("21.90"));
        when(repositorio.descontoParaCobranca(pedido)).thenReturn(new DescontoCobranca(new BigDecimal("11.67"),
                new BigDecimal("21.90")));

        var itens = servico.itensParaCobranca(pedido);
        BigDecimal frete = servico.freteParaCobranca(pedido);

        assertThat(itens).singleElement().satisfies(item -> {
            assertThat(item.slug()).isEqualTo(PedidoParaPagamentoService.LINHA_PECAS_COM_CUPOM);
            assertThat(item.quantidade()).isEqualTo(1);
            assertThat(item.preco()).isEqualByComparingTo("104.99");
        });
        assertThat(frete).isEqualByComparingTo("0.00");
    }

    @Test
    void cobrancaSemDescontoMantemAsLinhasOriginais() {
        var repositorio = mock(PedidoFinanceiroRepository.class);
        var servico = new PedidoParaPagamentoService(repositorio, mock(ProcessarPedidosPendentesUseCase.class));
        UUID pedido = UUID.randomUUID();
        var originais = List.of(new ItemCobranca("vaso", 1, new BigDecimal("50.00")));
        when(repositorio.itensParaCobranca(pedido)).thenReturn(originais);
        when(repositorio.freteParaCobranca(pedido)).thenReturn(new BigDecimal("38.90"));
        when(repositorio.descontoParaCobranca(pedido)).thenReturn(new DescontoCobranca(BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(servico.itensParaCobranca(pedido)).isEqualTo(originais);
        assertThat(servico.freteParaCobranca(pedido)).isEqualByComparingTo("38.90");
    }
}
