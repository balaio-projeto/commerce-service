package br.com.balaio.commerce.integration.catalogo;

import br.com.balaio.commerce.cart.application.port.out.CotacaoSacola;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

@Component
public class CotacaoSacolaHttpAdapter implements CotacaoSacola {
    private final CatalogoCliente catalogo;

    public CotacaoSacolaHttpAdapter(CatalogoCliente catalogo) {
        this.catalogo = catalogo;
    }

    @Override
    public Cotacao cotarParaSacola(String slug) {
        try {
            var cotacao = catalogo.cotar(slug);
            return new Cotacao(cotacao.slug(), cotacao.preco(), cotacao.disponibilidade());
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict erro) {
            throw new ErroCotacao(Falha.PECA_INDISPONIVEL, erro);
        } catch (IllegalArgumentException | IllegalStateException erro) {
            throw new ErroCotacao(Falha.RESPOSTA_INVALIDA, erro);
        } catch (RestClientException erro) {
            throw new ErroCotacao(Falha.CATALOGO_INDISPONIVEL, erro);
        }
    }
}
