package br.com.balaio.commerce.promotion.domain;

import java.util.Locale;

public enum SituacaoCupom {
    ATIVO, AGENDADO, EXPIRADO, ESGOTADO, INATIVO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }
}
