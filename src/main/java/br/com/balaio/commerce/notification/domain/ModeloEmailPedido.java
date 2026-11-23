package br.com.balaio.commerce.notification.domain;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

public final class ModeloEmailPedido {
    private ModeloEmailPedido() { }

    public record Conteudo(String assunto, String texto) { }

    public static Conteudo compor(EnvioEmail envio, String nome, BigDecimal valorTotal, String rastreio,
            String transportadora, String frontend) {
        String codigo = envio.pedido().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String saudacao = nome == null || nome.isBlank() ? "Olá," : "Olá, " + nome + ",";
        String linkPedido = frontend + (envio.paraComprador() ? "/orders/" + envio.pedido() : "/dashboard");
        String total = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(valorTotal);
        String assunto;
        String corpo;
        switch (envio.evento() + ":" + envio.destinatario()) {
            case "pagamento_confirmado:comprador" -> {
                assunto = "Pagamento confirmado · pedido " + codigo;
                corpo = "O pagamento do pedido " + codigo + " (" + total + ") foi confirmado. "
                        + "O artesão já foi avisado e vai atualizar cada etapa por aqui.";
            }
            case "pagamento_confirmado:artesao" -> {
                assunto = "Novo pedido pago · " + codigo;
                corpo = "Você recebeu o pedido " + codigo + " com pagamento confirmado (" + total + "). "
                        + "Aceite o pedido no painel para começar a produção.";
            }
            case "enviado:comprador" -> {
                assunto = "Pedido enviado · " + codigo;
                corpo = "O pedido " + codigo + " foi enviado. Código de rastreio: " + rastreio
                        + (transportadora == null ? "" : " (" + transportadora + ")") + ".";
            }
            case "entregue:comprador" -> {
                assunto = "Pedido entregue · " + codigo;
                corpo = "O pedido " + codigo + " foi marcado como entregue. Conte como foi a compra avaliando o pedido.";
            }
            default -> throw new IllegalStateException("Evento de e-mail desconhecido.");
        }
        return new Conteudo("Balaio · " + assunto, saudacao + "\n\n" + corpo + "\n\nAcompanhe em: " + linkPedido
                + "\n\nEsta mensagem é automática; não responda a este endereço.\n");
    }
}
