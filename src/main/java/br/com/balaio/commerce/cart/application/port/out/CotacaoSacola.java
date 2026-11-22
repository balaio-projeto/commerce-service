package br.com.balaio.commerce.cart.application.port.out;

import java.math.BigDecimal;

public interface CotacaoSacola {
    Cotacao cotarParaSacola(String slug);

    record Cotacao(String slug, BigDecimal preco, String disponibilidade) { }

    enum Falha { PECA_INDISPONIVEL, RESPOSTA_INVALIDA, CATALOGO_INDISPONIVEL }

    class ErroCotacao extends RuntimeException {
        private final Falha falha;

        public ErroCotacao(Falha falha, RuntimeException causa) {
            super(causa);
            this.falha = falha;
        }

        public Falha falha() { return falha; }
    }
}
