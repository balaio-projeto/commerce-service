package br.com.balaio.commerce.order.domain;

public record FreteContratado(String nome, String provedor, String transportadora, String prazo) {
    public static final String PROVEDOR_ENTREGA_COMBINADA = "balaio_local";

    public String previsaoEntrega() {
        if (prazo == null) return null;
        return entregaCombinada() ? prazo : prazo + " após o envio";
    }

    public boolean entregaCombinada() {
        return PROVEDOR_ENTREGA_COMBINADA.equals(provedor);
    }
}
