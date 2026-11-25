package br.com.balaio.commerce.notification.domain;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModeloEmailPedido {
    private ModeloEmailPedido() { }

    public record Aviso(String titulo, String mensagem, String link) { }

    public record Conteudo(String assunto, EmailBalaio email, Aviso aviso) { }

    public record Pedido(BigDecimal total, String rastreio, String transportadora, String motivo) { }

    private static final String ESTORNO =
            "Se houve pagamento, o estorno foi solicitado ao Mercado Pago. O prazo para o valor voltar depende do meio de pagamento.";

    public static Conteudo compor(EnvioEmail envio, String nome, Pedido pedido) {
        String codigo = envio.pedido().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String total = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(pedido.total());
        String linkPedido = envio.paraComprador() ? "/orders/" + envio.pedido() : "/dashboard";
        String ola = nome == null || nome.isBlank() ? "Olá." : "Olá, " + primeiroNome(nome) + ".";
        String motivo = pedido.motivo() == null || pedido.motivo().isBlank() ? null : pedido.motivo().trim();
        var detalhes = new ArrayList<EmailBalaio.Detalhe>();
        detalhes.add(new EmailBalaio.Detalhe("Pedido", codigo));
        detalhes.add(new EmailBalaio.Detalhe("Total", total));

        return switch (envio.evento() + ":" + envio.destinatario()) {
            case "pagamento_confirmado:comprador" -> conteudo("Pagamento confirmado · pedido " + codigo,
                    "Pagamento confirmado", List.of(ola + " O pagamento do pedido " + codigo + " foi confirmado.",
                            "O artesão já foi avisado e vai atualizar cada etapa da produção e do envio."),
                    detalhes, "Acompanhar pedido", linkPedido,
                    new Aviso("Pagamento confirmado", "O pagamento do pedido " + codigo + " (" + total + ") foi confirmado.",
                            linkPedido));
            case "pagamento_confirmado:artesao" -> conteudo("Novo pedido pago · " + codigo,
                    "Você tem um novo pedido", List.of(ola + " O pedido " + codigo + " foi pago e aguarda a sua confirmação.",
                            "Aceite o pedido no painel para começar a produção."),
                    detalhes, "Ver pedidos pendentes", linkPedido,
                    new Aviso("Novo pedido pago", "O pedido " + codigo + " (" + total
                            + ") foi pago. Aceite no painel para começar a produção.", linkPedido));
            case "falha_pagamento:comprador" -> conteudo("Pagamento não aprovado · pedido " + codigo,
                    "Pagamento não aprovado", List.of(ola + " O pagamento do pedido " + codigo
                            + " não foi aprovado pelo Mercado Pago e o pedido foi encerrado.",
                            "Se ainda quiser as peças, faça um novo pedido com outro meio de pagamento."),
                    detalhes, "Ver pedido", linkPedido,
                    new Aviso("Pagamento não aprovado", "O pagamento do pedido " + codigo + " não foi aprovado.", linkPedido));
            case "producao:comprador" -> conteudo("Pedido em produção · " + codigo,
                    "Seu pedido está em produção", List.of(ola + " O artesão aceitou o pedido " + codigo
                            + " e começou a produção.", "Você recebe um aviso quando ele for enviado."),
                    List.of(detalhes.getFirst()), "Acompanhar pedido", linkPedido,
                    new Aviso("Pedido em produção", "O artesão começou a produzir o pedido " + codigo + ".", linkPedido));
            case "enviado:comprador" -> {
                var envioDetalhes = new ArrayList<EmailBalaio.Detalhe>(List.of(detalhes.getFirst()));
                if (pedido.rastreio() != null) envioDetalhes.add(new EmailBalaio.Detalhe("Código de rastreio", pedido.rastreio()));
                if (pedido.transportadora() != null)
                    envioDetalhes.add(new EmailBalaio.Detalhe("Transportadora", pedido.transportadora()));
                yield conteudo("Pedido enviado · " + codigo, "Seu pedido foi enviado",
                        List.of(ola + " O pedido " + codigo + " saiu da oficina do artesão.",
                                "Acompanhe a entrega pelo código de rastreio na página do pedido."),
                        envioDetalhes, "Acompanhar entrega", linkPedido,
                        new Aviso("Pedido enviado", "O pedido " + codigo + " foi enviado"
                                + (pedido.rastreio() == null ? "." : ". Rastreio: " + pedido.rastreio() + "."), linkPedido));
            }
            case "entregue:comprador" -> conteudo("Pedido entregue · " + codigo, "Seu pedido foi entregue",
                    List.of(ola + " O pedido " + codigo + " foi marcado como entregue.",
                            "Conte como foi a compra. Sua avaliação ajuda outros compradores e o artesão."),
                    List.of(detalhes.getFirst()), "Avaliar compra", linkPedido + "/review",
                    new Aviso("Pedido entregue", "O pedido " + codigo + " foi entregue. Avalie a sua compra.",
                            linkPedido + "/review"));
            case "recusado:comprador" -> conteudo("Pedido recusado · " + codigo, "O artesão recusou o pedido",
                    comMotivo(List.of(ola + " O artesão não pôde aceitar o pedido " + codigo + "."), motivo, ESTORNO),
                    detalhes, "Ver pedido", linkPedido,
                    new Aviso("Pedido recusado", "O artesão recusou o pedido " + codigo + "."
                            + (motivo == null ? "" : " Motivo: " + motivo), linkPedido));
            case "cancelado:comprador" -> conteudo("Pedido cancelado · " + codigo, "Pedido cancelado",
                    comMotivo(List.of(ola + " O pedido " + codigo + " foi cancelado."), motivo, ESTORNO),
                    detalhes, "Ver pedido", linkPedido,
                    new Aviso("Pedido cancelado", "O pedido " + codigo + " foi cancelado.", linkPedido));
            case "cancelado:artesao" -> conteudo("Pedido cancelado · " + codigo, "Um pedido foi cancelado",
                    comMotivo(List.of(ola + " O pedido " + codigo + " foi cancelado e não precisa mais ser produzido."),
                            motivo, null),
                    detalhes, "Abrir painel", linkPedido,
                    new Aviso("Pedido cancelado", "O pedido " + codigo + " foi cancelado. Não é preciso produzi-lo.",
                            linkPedido));
            case "reembolsado:comprador" -> conteudo("Reembolso concluído · pedido " + codigo, "Reembolso concluído",
                    comMotivo(List.of(ola + " O valor do pedido " + codigo + " foi reembolsado pelo Mercado Pago."), motivo,
                            "O prazo para o valor aparecer depende do meio de pagamento."),
                    detalhes, "Ver pedido", linkPedido,
                    new Aviso("Reembolso concluído", "O valor do pedido " + codigo + " (" + total + ") foi reembolsado.",
                            linkPedido));
            default -> throw new IllegalStateException("Evento de e-mail desconhecido.");
        };
    }

    private static Conteudo conteudo(String assunto, String titulo, List<String> paragrafos,
            List<EmailBalaio.Detalhe> detalhes, String acao, String link, Aviso aviso) {
        String assuntoCompleto = "Balaio · " + assunto;
        return new Conteudo(assuntoCompleto,
                new EmailBalaio(assuntoCompleto, aviso.mensagem(), titulo, paragrafos, detalhes, acao, link, null), aviso);
    }

    private static List<String> comMotivo(List<String> inicio, String motivo, String fim) {
        var paragrafos = new ArrayList<>(inicio);
        if (motivo != null) paragrafos.add("Motivo informado: " + motivo);
        if (fim != null) paragrafos.add(fim);
        return paragrafos;
    }

    private static String primeiroNome(String nome) {
        String limpo = nome.trim();
        int espaco = limpo.indexOf(' ');
        return espaco > 0 ? limpo.substring(0, espaco) : limpo;
    }
}
