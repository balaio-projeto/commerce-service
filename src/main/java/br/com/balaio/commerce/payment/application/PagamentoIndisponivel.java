package br.com.balaio.commerce.payment.application;

public class PagamentoIndisponivel extends RuntimeException {
    public PagamentoIndisponivel() {
        super("Pagamento não configurado.");
    }
}
