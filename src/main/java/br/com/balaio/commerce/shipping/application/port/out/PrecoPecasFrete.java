package br.com.balaio.commerce.shipping.application.port.out;

import java.math.BigDecimal;

public interface PrecoPecasFrete {
    BigDecimal precoAtual(String slug);
}
