package br.com.balaio.commerce.order.application;

public class PedidoException extends RuntimeException {
    public enum Tipo {
        REQUISICAO_INVALIDA, SEM_PERMISSAO, PEDIDO_NAO_ENCONTRADO, PEDIDO_CONFLITANTE,
        CHECKOUT_CONFLITANTE, MEDIACAO_NECESSARIA, MEDIACAO_CONFLITANTE, RESERVA_CONFLITANTE,
        ENDERECO_NAO_ENCONTRADO, ENDERECO_INCOMPLETO, PERFIL_INDISPONIVEL, RASTREIO_INVALIDO, PECA_DO_PROPRIO_ATELIE
    }

    private final Tipo tipo;

    public PedidoException(Tipo tipo, String mensagem) {
        super(mensagem);
        this.tipo = tipo;
    }

    public Tipo tipo() { return tipo; }
    public String codigo() { return tipo.name(); }
}
