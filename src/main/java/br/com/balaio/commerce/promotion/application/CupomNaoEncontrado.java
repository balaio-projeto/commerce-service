package br.com.balaio.commerce.promotion.application;

public class CupomNaoEncontrado extends RuntimeException {
    public CupomNaoEncontrado() {
        super("Cupom não encontrado.");
    }
}
