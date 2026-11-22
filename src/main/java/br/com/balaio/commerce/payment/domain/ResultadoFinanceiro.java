package br.com.balaio.commerce.payment.domain;

import java.math.BigDecimal;
import java.util.List;

public record ResultadoFinanceiro(String status, BigDecimal valorReembolsado, boolean estornoConfirmado) {
    public static ResultadoFinanceiro conciliar(String idAnterior, String statusAnterior, BigDecimal estornoAnterior,
            String idRecebido, String statusRecebido, BigDecimal estornoRecebido, BigDecimal total) {
        if (idAnterior != null && !idAnterior.isBlank() && !idAnterior.equals(idRecebido) && !falhou(statusAnterior))
            throw new IllegalStateException("Outra cobrança já vinculada ao pedido.");

        boolean statusEstornado = "refunded".equals(statusAnterior) || "refunded".equals(statusRecebido);
        BigDecimal acumulado = (estornoAnterior == null ? BigDecimal.ZERO : estornoAnterior)
                .max("refunded".equals(statusRecebido) ? total : estornoRecebido);
        boolean estornado = statusEstornado || acumulado.compareTo(total) >= 0;
        String status = statusEstornado ? "refunded" : estornado ? "approved" : statusRecebido;
        return new ResultadoFinanceiro(status, acumulado, estornado);
    }

    public static boolean falhou(String status) {
        return status != null && List.of("rejected", "cancelled", "expired").contains(status);
    }
}
