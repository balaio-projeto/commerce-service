package br.com.balaio.commerce.promotion.application;

import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.RecusaCupom;

public class CupomRecusado extends RuntimeException {
    private final RecusaCupom motivo;

    public CupomRecusado(RecusaCupom motivo) {
        this(Cupom.Recusa.de(motivo));
    }

    public CupomRecusado(Cupom.Recusa recusa) {
        super(recusa.mensagem());
        this.motivo = recusa.motivo();
    }

    public RecusaCupom motivo() {
        return motivo;
    }

    public String codigo() {
        return motivo.codigo();
    }
}
