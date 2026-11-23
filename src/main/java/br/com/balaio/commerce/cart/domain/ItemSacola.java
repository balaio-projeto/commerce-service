package br.com.balaio.commerce.cart.domain;

import java.util.Set;

public record ItemSacola(String slug, Integer quantidade) {
    public boolean valido() {
        return slug != null && slug.length() <= 160 && slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                && quantidade != null && quantidade >= 1 && quantidade <= 3;
    }

    public boolean quantidadeDisponivel(String disponibilidade) {
        return !"unica".equals(disponibilidade) || quantidade == 1;
    }

    public static boolean disponibilidadeValida(String disponibilidade) {
        return disponibilidade != null && Set.of("unica", "encomenda", "disponivel").contains(disponibilidade);
    }
}
