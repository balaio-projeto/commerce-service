package br.com.balaio.commerce.promotion.domain;

public enum RecusaCupom {
    INVALIDO("CUPOM_INVALIDO", "Cupom inválido. Confira o código digitado."),
    INATIVO("CUPOM_INATIVO", "Este cupom não está mais disponível."),
    AINDA_NAO_VALIDO("CUPOM_AINDA_NAO_VALIDO", "Este cupom ainda não começou a valer."),
    EXPIRADO("CUPOM_EXPIRADO", "Este cupom expirou."),
    ESGOTADO("CUPOM_ESGOTADO", "Este cupom esgotou: todos os usos disponíveis já foram aproveitados."),
    LIMITE_POR_COMPRADOR("CUPOM_LIMITE_COMPRADOR", "Você já usou este cupom o número máximo de vezes permitido."),
    PEDIDO_MINIMO("CUPOM_PEDIDO_MINIMO", "Este cupom vale para pedidos a partir de %s em peças."),
    SEM_DESCONTO("CUPOM_SEM_DESCONTO", "Este cupom é de frete grátis, mas a entrega escolhida já não tem custo."),
    PROMOCAO_ENCERRADA("PROMOCAO_ENCERRADA", "A promoção automática acabou de encerrar. Revise o total e confirme novamente.");

    private final String codigo;
    private final String mensagem;

    RecusaCupom(String codigo, String mensagem) {
        this.codigo = codigo;
        this.mensagem = mensagem;
    }

    public String codigo() {
        return codigo;
    }

    public String mensagem(Object... detalhes) {
        return detalhes.length == 0 ? mensagem : mensagem.formatted(detalhes);
    }
}
