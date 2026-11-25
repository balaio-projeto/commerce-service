package br.com.balaio.commerce.notification.domain;

import java.util.List;

public record EmailBalaio(String assunto, String resumo, String titulo, List<String> paragrafos,
        List<Detalhe> detalhes, String acaoTexto, String acaoLink, String nota) {

    public record Detalhe(String rotulo, String valor) { }

    private static final String COR_CABECALHO = "#7c2f0d";
    private static final String COR_FAIXA = "#ffcb05";
    private static final String COR_FUNDO = "#f7f5f1";
    private static final String COR_TINTA = "#111625";
    private static final String COR_TINTA_SUAVE = "#5b6475";
    private static final String COR_LINHA = "#e7e2d9";
    private static final String COR_ACAO = "#a23e12";
    private static final String COR_BARRO_FUNDO = "#f5ece2";
    private static final String FONTE_CORPO = "'Open Sans', 'Segoe UI', Helvetica, Arial, sans-serif";
    private static final String FONTE_TITULO = "'Poiret One', 'Trebuchet MS', 'Segoe UI', Arial, sans-serif";

    public EmailBalaio {
        paragrafos = paragrafos == null ? List.of() : List.copyOf(paragrafos);
        detalhes = detalhes == null ? List.of() : List.copyOf(detalhes);
    }

    public String html(String frontendBaseUrl) {
        String base = semBarraFinal(frontendBaseUrl);
        var html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"light\">")
                .append("<title>").append(escapar(assunto)).append("</title>")
                .append("<link href=\"https://fonts.googleapis.com/css2?family=Open+Sans:wght@400;600;700&amp;family=Poiret+One&amp;display=swap\" rel=\"stylesheet\">")
                .append("</head><body style=\"margin:0;padding:0;background:").append(COR_FUNDO).append(";\">")
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">").append(escapar(resumo)).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
                .append(COR_FUNDO).append(";\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;\">");

        html.append("<tr><td style=\"background:").append(COR_CABECALHO)
                .append(";border-radius:16px 16px 0 0;padding:20px 28px;\"><a href=\"").append(escapar(base))
                .append("\" style=\"text-decoration:none;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                .append("<td style=\"vertical-align:middle;padding-right:12px;\"><img src=\"").append(escapar(base))
                .append("/balaio-logo.png\" width=\"44\" height=\"44\" alt=\"\" style=\"display:block;border:0;\"></td>")
                .append("<td style=\"vertical-align:middle;\"><div style=\"font-family:").append(FONTE_TITULO)
                .append(";font-size:28px;line-height:30px;color:#ffffff;\">Balaio</div><div style=\"font-family:")
                .append(FONTE_CORPO).append(";font-size:11px;letter-spacing:1.5px;font-weight:700;color:").append(COR_FAIXA)
                .append(";\">ARTESANATO DE PERNAMBUCO</div></td></tr></table></a></td></tr>")
                .append("<tr><td style=\"height:4px;line-height:4px;font-size:0;background:").append(COR_FAIXA).append(";\">&nbsp;</td></tr>");

        html.append("<tr><td style=\"background:#ffffff;padding:32px 28px 28px;border-left:1px solid ").append(COR_LINHA)
                .append(";border-right:1px solid ").append(COR_LINHA).append(";\">")
                .append("<h1 style=\"margin:0 0 16px;font-family:").append(FONTE_CORPO)
                .append(";font-size:22px;line-height:30px;font-weight:700;color:").append(COR_TINTA).append(";\">")
                .append(escapar(titulo)).append("</h1>");
        for (String paragrafo : paragrafos) {
            html.append("<p style=\"margin:0 0 14px;font-family:").append(FONTE_CORPO)
                    .append(";font-size:15px;line-height:24px;color:").append(COR_TINTA).append(";\">")
                    .append(escapar(paragrafo)).append("</p>");
        }
        if (!detalhes.isEmpty()) {
            html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 20px;background:")
                    .append(COR_BARRO_FUNDO).append(";border-radius:12px;\">");
            for (Detalhe detalhe : detalhes) {
                html.append("<tr><td style=\"padding:10px 16px;font-family:").append(FONTE_CORPO)
                        .append(";font-size:13px;color:").append(COR_TINTA_SUAVE).append(";\">").append(escapar(detalhe.rotulo()))
                        .append("</td><td align=\"right\" style=\"padding:10px 16px;font-family:").append(FONTE_CORPO)
                        .append(";font-size:14px;font-weight:600;color:").append(COR_TINTA).append(";\">")
                        .append(escapar(detalhe.valor())).append("</td></tr>");
            }
            html.append("</table>");
        }
        if (acaoTexto != null && acaoLink != null) {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 4px;\"><tr>")
                    .append("<td style=\"border-radius:999px;background:").append(COR_ACAO).append(";\"><a href=\"")
                    .append(escapar(link(base, acaoLink))).append("\" style=\"display:inline-block;padding:13px 28px;font-family:")
                    .append(FONTE_CORPO).append(";font-size:15px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:999px;\">")
                    .append(escapar(acaoTexto)).append("</a></td></tr></table>");
        }
        if (nota != null && !nota.isBlank()) {
            html.append("<p style=\"margin:20px 0 0;font-family:").append(FONTE_CORPO).append(";font-size:13px;line-height:20px;color:")
                    .append(COR_TINTA_SUAVE).append(";\">").append(escapar(nota)).append("</p>");
        }
        html.append("</td></tr>");

        html.append("<tr><td style=\"background:#ffffff;border:1px solid ").append(COR_LINHA)
                .append(";border-top:0;border-radius:0 0 16px 16px;padding:18px 28px;\"><p style=\"margin:0;font-family:")
                .append(FONTE_CORPO).append(";font-size:12px;line-height:18px;color:").append(COR_TINTA_SUAVE).append(";\">")
                .append("Esta mensagem é automática; não responda a este endereço.<br>")
                .append("<a href=\"").append(escapar(base)).append("\" style=\"color:").append(COR_ACAO)
                .append(";text-decoration:none;font-weight:600;\">Balaio</a> · Artesanato de Pernambuco</p></td></tr>");

        html.append("</table></td></tr></table></body></html>");
        return html.toString();
    }

    public String texto(String frontendBaseUrl) {
        String base = semBarraFinal(frontendBaseUrl);
        var texto = new StringBuilder(titulo).append("\n\n");
        for (String paragrafo : paragrafos) texto.append(paragrafo).append("\n\n");
        for (Detalhe detalhe : detalhes) texto.append(detalhe.rotulo()).append(": ").append(detalhe.valor()).append('\n');
        if (!detalhes.isEmpty()) texto.append('\n');
        if (acaoTexto != null && acaoLink != null) texto.append(acaoTexto).append(": ").append(link(base, acaoLink)).append("\n\n");
        if (nota != null && !nota.isBlank()) texto.append(nota).append("\n\n");
        return texto.append("Esta mensagem é automática; não responda a este endereço.\nBalaio · Artesanato de Pernambuco\n")
                .toString();
    }

    private static String link(String base, String destino) {
        return destino.startsWith("http://") || destino.startsWith("https://") ? destino : base + destino;
    }

    private static String semBarraFinal(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    static String escapar(String valor) {
        if (valor == null) return "";
        var saida = new StringBuilder(valor.length() + 16);
        for (char c : valor.toCharArray()) {
            switch (c) {
                case '&' -> saida.append("&amp;");
                case '<' -> saida.append("&lt;");
                case '>' -> saida.append("&gt;");
                case '"' -> saida.append("&quot;");
                case '\'' -> saida.append("&#39;");
                default -> saida.append(c);
            }
        }
        return saida.toString();
    }
}
