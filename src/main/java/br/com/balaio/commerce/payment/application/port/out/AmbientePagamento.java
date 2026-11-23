package br.com.balaio.commerce.payment.application.port.out;

public interface AmbientePagamento {
    boolean enabled();

    boolean producao();
}
