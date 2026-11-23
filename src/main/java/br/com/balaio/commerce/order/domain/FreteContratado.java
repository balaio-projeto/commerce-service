package br.com.balaio.commerce.order.domain;

public record FreteContratado(String nome, String provedor, String transportadora, String prazo) {
    public String previsaoEntrega() {
        return prazo == null ? null : prazo + " após o envio";
    }
}
