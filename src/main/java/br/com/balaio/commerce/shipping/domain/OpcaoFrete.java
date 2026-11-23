package br.com.balaio.commerce.shipping.domain;

import java.math.BigDecimal;

public record OpcaoFrete(String id, String nome, String prazo, Integer prazoDiasMinimo, Integer prazoDiasMaximo,
        BigDecimal valor, String transportadora, String provedor) {

    public static String prazoEmTexto(Integer minimo, Integer maximo) {
        if (minimo == null && maximo == null) return "Prazo informado pela transportadora";
        int inicio = minimo == null ? maximo : minimo;
        int fim = maximo == null ? inicio : maximo;
        if (inicio == fim) return inicio + (inicio == 1 ? " dia útil" : " dias úteis");
        return inicio + " a " + fim + " dias úteis";
    }
}
