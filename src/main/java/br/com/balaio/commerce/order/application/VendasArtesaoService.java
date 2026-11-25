package br.com.balaio.commerce.order.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ConsultarVendasArtesaoUseCase;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores.PerfilComprador;

@Service
public class VendasArtesaoService implements ConsultarVendasArtesaoUseCase {
    private final ConsultarPedidosUseCase pedidos;
    private final PerfisCompradores perfis;
    private final PedidoRepository repositorio;

    public VendasArtesaoService(ConsultarPedidosUseCase pedidos, PerfisCompradores perfis, PedidoRepository repositorio) {
        this.pedidos = pedidos;
        this.perfis = perfis;
        this.repositorio = repositorio;
    }

    @Override
    public List<Venda> vendas(UUID artesao) {
        var vendas = pedidos.listarVendasArtesao(artesao);
        var compradores = consultarPerfis(vendas.stream().map(ConsultarPedidosUseCase.Resumo::compradorId).toList());
        return vendas.stream().map(venda -> new Venda(venda, perfilDe(compradores, venda.compradorId()))).toList();
    }

    @Override
    public List<PendenteComComprador> pendentes(UUID artesao) {
        var pendentes = pedidos.listarPendentesArtesao(artesao);
        var compradores = consultarPerfis(pendentes.stream().map(ConsultarPedidosUseCase.Pendente::compradorId).toList());
        return pendentes.stream()
                .map(pendente -> new PendenteComComprador(pendente, perfilDe(compradores, pendente.compradorId())))
                .toList();
    }

    @Override
    public Venda venda(UUID id, UUID artesao) {
        var pedido = pedidos.consultarArtesao(id, artesao);
        return new Venda(pedido, perfilDe(consultarPerfis(List.of(pedido.compradorId())), pedido.compradorId()),
                repositorio.endereco(pedido.id()));
    }

    private Map<UUID, PerfilComprador> consultarPerfis(Collection<UUID> compradores) {
        var unicos = compradores.stream().distinct().toList();
        return unicos.isEmpty() ? Map.of() : perfis.consultar(unicos);
    }

    private static PerfilComprador perfilDe(Map<UUID, PerfilComprador> perfis, UUID comprador) {
        return perfis.getOrDefault(comprador, PerfilComprador.DESCONHECIDO);
    }
}
