package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.order.application.VendasArtesaoService;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Entrega;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Pendente;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores.PerfilComprador;

class VendasArtesaoServiceTest {
    private final ConsultarPedidosUseCase pedidos = mock(ConsultarPedidosUseCase.class);
    private final PerfisCompradores perfis = mock(PerfisCompradores.class);
    private final PedidoRepository repositorio = mock(PedidoRepository.class);
    private final VendasArtesaoService servico = new VendasArtesaoService(pedidos, perfis, repositorio);
    private final UUID artesao = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID bruno = UUID.randomUUID();

    @Test
    void agrupaCompradoresDasVendasNumaUnicaConsultaEMantemCadaPedidoUmaVez() {
        var primeiro = resumo(ana);
        var segundo = resumo(ana);
        var terceiro = resumo(bruno);
        when(pedidos.listarVendasArtesao(artesao)).thenReturn(List.of(primeiro, segundo, terceiro));
        when(perfis.consultar(List.of(ana, bruno))).thenReturn(Map.of(ana, new PerfilComprador("Ana", "/foto-ana")));

        var vendas = servico.vendas(artesao);

        assertThat(vendas).extracting(venda -> venda.pedido().id())
                .containsExactly(primeiro.id(), segundo.id(), terceiro.id());
        assertThat(vendas).extracting(venda -> venda.comprador().nome()).containsExactly("Ana", "Ana", null);
        assertThat(vendas.get(2).comprador()).isEqualTo(PerfilComprador.DESCONHECIDO);
        verify(perfis).consultar(List.of(ana, bruno));
    }

    @Test
    void detalheDaVendaTrazOEnderecoDeEntregaSoParaOArtesaoDono() {
        var pedido = resumo(ana);
        var endereco = Map.of("cep", "50050-000", "endereco", "Rua da Aurora, 240, Boa Vista", "cidade", "Recife", "estado", "PE");
        when(pedidos.consultarArtesao(pedido.id(), artesao)).thenReturn(pedido);
        when(perfis.consultar(List.of(ana))).thenReturn(Map.of(ana, new PerfilComprador("Ana", null)));
        when(repositorio.endereco(pedido.id())).thenReturn(endereco);

        var venda = servico.venda(pedido.id(), artesao);

        assertThat(venda.enderecoEntrega()).isEqualTo(endereco);
        assertThat(servico.vendas(artesao)).allSatisfy(item -> assertThat(item.enderecoEntrega()).isNull());
    }

    @Test
    void pendentesTrazemNomeEFotoDoComprador() {
        var pendente = new Pendente(UUID.randomUUID(), "vaso", OffsetDateTime.now(), new BigDecimal("80.00"), bruno);
        when(pedidos.listarPendentesArtesao(artesao)).thenReturn(List.of(pendente));
        when(perfis.consultar(List.of(bruno))).thenReturn(Map.of(bruno, new PerfilComprador("Bruno", "/foto-bruno")));

        var pendentes = servico.pendentes(artesao);

        assertThat(pendentes).singleElement().satisfies(item -> {
            assertThat(item.pendente()).isEqualTo(pendente);
            assertThat(item.comprador()).isEqualTo(new PerfilComprador("Bruno", "/foto-bruno"));
        });
    }

    @Test
    void listaVaziaNaoConsultaOPerfil() {
        when(pedidos.listarVendasArtesao(artesao)).thenReturn(List.of());
        assertThat(servico.vendas(artesao)).isEmpty();
        verify(perfis, never()).consultar(any());
    }

    @Test
    void pedidoDoArtesaoTrazOComprador() {
        var pedido = resumo(ana);
        when(pedidos.consultarArtesao(pedido.id(), artesao)).thenReturn(pedido);
        when(perfis.consultar(List.of(ana))).thenReturn(Map.of(ana, new PerfilComprador("Ana", null)));

        var venda = servico.venda(pedido.id(), artesao);

        assertThat(venda.pedido()).isEqualTo(pedido);
        assertThat(venda.comprador().nome()).isEqualTo("Ana");
    }

    private Resumo resumo(UUID comprador) {
        return new Resumo(UUID.randomUUID(), comprador, artesao, "vaso", OffsetDateTime.now(), "producao",
                new BigDecimal("80.00"), null, false, List.of(), List.of(),
                new Entrega(BigDecimal.TEN, null, null, null, null, null));
    }
}
