package br.com.balaio.commerce.shared.security;

public class AcessoNegado extends RuntimeException {
    public AcessoNegado(String mensagem) {
        super(mensagem);
    }
}
