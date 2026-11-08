package br.com.balaio.commerce.order;

public class PedidoException extends RuntimeException {
    private final int status;
    private final String codigo;

    public PedidoException(int status, String codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }

    public int status() { return status; }
    public String codigo() { return codigo; }
}
