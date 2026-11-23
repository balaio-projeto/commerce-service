package br.com.balaio.commerce.order.domain;

import java.util.List;

public final class EstadoPedido {
    private EstadoPedido() { }

    public static boolean producaoIniciada(String estado) {
        return List.of("producao", "enviado", "entregue").contains(estado);
    }

    public static boolean permiteProducao(String estado) {
        return "confirmado".equals(estado);
    }

    public static String anteriorExigidoPara(String destino) {
        return switch (destino == null ? "" : destino) {
            case "producao" -> "confirmado";
            case "enviado" -> "producao";
            case "entregue" -> "enviado";
            default -> null;
        };
    }

    public static boolean permiteMediacao(String estado) {
        return permiteProducao(estado) || producaoIniciada(estado);
    }

    public static boolean permiteCancelamento(String estado) {
        return List.of("aguardando_reserva", "aguardando_pagamento", "confirmado").contains(estado);
    }

    public static boolean encerramentoPendente(String estado) {
        return List.of("encerramento_pendente", "cancelamento_pendente", "reembolso_pendente").contains(estado);
    }

    public static String iniciarEncerramento(String estado) {
        return switch (estado) {
            case "aguardando_pagamento" -> "cancelamento_pendente";
            case "aguardando_reserva" -> "encerramento_pendente";
            default -> "reembolso_pendente";
        };
    }

    public static String concluirEncerramento(String causa) {
        return "mediacao_cancelar_reembolsar".equals(causa) ? "reembolsado" : causa;
    }

    public static boolean etapaConcluida(String estado) {
        return !estado.endsWith("pendente") && !estado.startsWith("aguardando");
    }

    public static boolean encerrarAposEstorno(String estado) {
        return !List.of("cancelado", "recusado", "reembolsado", "falha_reserva", "falha_pagamento").contains(estado);
    }

    public static boolean estornarAprovacaoTardia(String estado) {
        return List.of("cancelamento_pendente", "encerramento_pendente", "falha_pagamento",
                "cancelado", "recusado").contains(estado);
    }

    public static boolean encerrarAposFalhaPagamento(String estado) {
        return List.of("aguardando_pagamento", "cancelamento_pendente", "reembolso_pendente").contains(estado);
    }
}
