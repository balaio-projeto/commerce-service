package br.com.balaio.commerce.order.api;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.ConsultarVendasArtesaoUseCase.PendenteComComprador;
import br.com.balaio.commerce.order.application.port.in.ConsultarVendasArtesaoUseCase.Venda;
import br.com.balaio.commerce.order.application.port.out.PerfisCompradores.PerfilComprador;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record VendaArtesaoResponse(@JsonUnwrapped PedidoResponse pedido, CompradorResponse comprador,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object enderecoEntrega) {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static VendaArtesaoResponse de(Venda venda) {
        return new VendaArtesaoResponse(PedidoResponse.de(venda.pedido(), venda.comprador().nome()),
                CompradorResponse.de(venda.comprador()), venda.enderecoEntrega());
    }

    public record CompradorResponse(String nome, String imagem) {
        static CompradorResponse de(PerfilComprador perfil) {
            return new CompradorResponse(perfil.nome(), perfil.imagem());
        }
    }

    public record PendenteResponse(UUID id, String pecaSlug, CompradorResponse comprador, String quando,
            BigDecimal valor) {
        public static PendenteResponse de(PendenteComComprador item) {
            var pendente = item.pendente();
            return new PendenteResponse(pendente.id(), pendente.pecaSlug(), CompradorResponse.de(item.comprador()),
                    pendente.criadoEm().format(DATA), pendente.valor());
        }
    }
}
