package br.com.balaio.commerce.integration.catalogo;

import java.math.BigDecimal;

import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.port.out.PrecoPecasFrete;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

@Component
public class PrecoPecasFreteHttpAdapter implements PrecoPecasFrete {
    private final CatalogoCliente catalogo;

    public PrecoPecasFreteHttpAdapter(CatalogoCliente catalogo) {
        this.catalogo = catalogo;
    }

    @Override
    public BigDecimal precoAtual(String slug) {
        try {
            return catalogo.cotar(slug).preco();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict indisponivel) {
            throw new FreteException(FreteException.Tipo.PECA_INDISPONIVEL,
                    "Uma das peças não está disponível para cotação.");
        } catch (RestClientException | IllegalArgumentException | IllegalStateException falha) {
            throw new FreteException(FreteException.Tipo.CATALOGO_INDISPONIVEL, "Catálogo indisponível; tente novamente.");
        }
    }
}
