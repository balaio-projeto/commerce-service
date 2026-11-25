package br.com.balaio.commerce.promotion.domain;

import java.math.BigDecimal;

public record DescontoCalculado(BigDecimal produtos, BigDecimal frete) {
    public static final DescontoCalculado NENHUM = new DescontoCalculado(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

    public BigDecimal total() {
        return produtos.add(frete);
    }

    public boolean vazio() {
        return total().signum() == 0;
    }
}
