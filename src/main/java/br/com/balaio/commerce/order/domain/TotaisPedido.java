package br.com.balaio.commerce.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TotaisPedido {
    public static final BigDecimal MINIMO_COBRADO = new BigDecimal("1.00");

    private TotaisPedido() { }

    public static Descontos limitar(BigDecimal subtotalProdutos, BigDecimal frete, BigDecimal descontoProdutos,
            BigDecimal descontoFrete) {
        BigDecimal margem = subtotalProdutos.add(frete).subtract(MINIMO_COBRADO).max(BigDecimal.ZERO);
        BigDecimal noFrete = descontoFrete.max(BigDecimal.ZERO).min(frete).min(margem);
        BigDecimal noProdutos = descontoProdutos.max(BigDecimal.ZERO).min(subtotalProdutos).min(margem.subtract(noFrete));
        return new Descontos(centavos(noProdutos), centavos(noFrete));
    }

    public static BigDecimal total(BigDecimal subtotalProdutos, BigDecimal frete, Descontos descontos) {
        return centavos(subtotalProdutos.add(frete).subtract(descontos.total()));
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    public record Descontos(BigDecimal produtos, BigDecimal frete) {
        public static final Descontos NENHUM = new Descontos(centavos(BigDecimal.ZERO), centavos(BigDecimal.ZERO));

        public BigDecimal total() {
            return produtos.add(frete);
        }

        public boolean vazio() {
            return total().signum() == 0;
        }

        public boolean mesmoValor(Descontos outro) {
            return produtos.compareTo(outro.produtos) == 0 && frete.compareTo(outro.frete) == 0;
        }
    }
}
