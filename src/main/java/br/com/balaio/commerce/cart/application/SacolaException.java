package br.com.balaio.commerce.cart.application;

public class SacolaException extends RuntimeException {
    public enum Tipo { REQUISICAO_INVALIDA, PECA_INDISPONIVEL, CATALOGO_INDISPONIVEL }

    private final Tipo tipo;

    public SacolaException(Tipo tipo, String mensagem) {
        super(mensagem);
        this.tipo = tipo;
    }

    public Tipo tipo() { return tipo; }
}
