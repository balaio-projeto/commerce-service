package br.com.balaio.commerce.shipping.application;

public class FreteException extends RuntimeException {
    public enum Tipo {
        CEP_INVALIDO("CEP_INVALIDO"),
        ITENS_INVALIDOS("REQUISICAO_INVALIDA"),
        PECA_INDISPONIVEL("PECA_INDISPONIVEL"),
        OPCAO_INDISPONIVEL("FRETE_INDISPONIVEL"),
        COTACAO_INDISPONIVEL("FRETE_INDISPONIVEL"),
        CATALOGO_INDISPONIVEL("CATALOGO_INDISPONIVEL");

        private final String codigo;

        Tipo(String codigo) {
            this.codigo = codigo;
        }
    }

    private final Tipo tipo;

    public FreteException(Tipo tipo, String mensagem) {
        super(mensagem);
        this.tipo = tipo;
    }

    public Tipo tipo() { return tipo; }
    public String codigo() { return tipo.codigo; }
}
