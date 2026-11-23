package br.com.balaio.commerce.shipping.domain;

import java.math.BigDecimal;

public final class FreteDemonstrativo {
    public static final String PROVEDOR = "fixo_demonstrativo";
    public static final String ID = "padrao";
    public static final BigDecimal VALOR = new BigDecimal("38.90");

    private FreteDemonstrativo() { }

    public static OpcaoFrete opcao() {
        return new OpcaoFrete(ID, "Entrega regional demonstrativa", "10 a 14 dias úteis", 10, 14,
                VALOR, null, PROVEDOR);
    }
}
