package br.com.balaio.commerce.shipping.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

public interface PrecoPecasFrete {
    PecaFrete consultar(String slug);

    record PecaFrete(BigDecimal preco, UUID artesao) { }
}
