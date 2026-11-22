package br.com.balaio.commerce.integration.catalogo;

import java.util.UUID;
import java.util.function.Supplier;

import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class CatalogoPedidosHttpAdapter implements CatalogoPedidos {
    private final CatalogoCliente catalogo;
    private final ObjectMapper json;

    public CatalogoPedidosHttpAdapter(CatalogoCliente catalogo, ObjectMapper json) {
        this.catalogo = catalogo;
        this.json = json;
    }

    @Override
    public Cotacao cotar(String slug) {
        return executar(() -> {
            var cotacao = catalogo.cotar(slug);
            return new Cotacao(cotacao.preco(), cotacao.disponibilidade(), cotacao.proprietarioId());
        }, false);
    }

    @Override
    public Reserva reservar(UUID pedido, String slug) {
        return executar(() -> {
            var reserva = catalogo.reservar(pedido, slug);
            return new Reserva(reserva.id(), reserva.estado(), reserva.preco(), reserva.proprietarioId());
        }, true);
    }

    @Override
    public String confirmar(UUID pedido, UUID reserva) {
        return executar(() -> catalogo.confirmar(pedido, reserva), false);
    }

    @Override
    public String liberar(UUID pedido, UUID reserva) {
        return executar(() -> catalogo.liberar(pedido, reserva), false);
    }

    private <T> T executar(Supplier<T> chamada, boolean reserva) {
        try {
            return chamada.get();
        } catch (HttpClientErrorException.Conflict erro) {
            throw new ErroCatalogo(reserva && pecaIndisponivel(erro) ? Falha.RESERVA_AUSENTE : Falha.CONFLITO, erro);
        } catch (RestClientException erro) {
            throw new ErroCatalogo(Falha.INDISPONIVEL, erro);
        } catch (IllegalArgumentException | IllegalStateException erro) {
            throw new ErroCatalogo(Falha.RESPOSTA_INVALIDA, erro);
        }
    }

    private boolean pecaIndisponivel(HttpClientErrorException.Conflict erro) {
        try {
            return "PECA_INDISPONIVEL".equals(json.readTree(erro.getResponseBodyAsString())
                    .path("erro").path("codigo").asText());
        } catch (JacksonException respostaInvalida) {
            return false;
        }
    }
}
