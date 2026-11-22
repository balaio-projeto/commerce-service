package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

public interface CatalogoPedidos {
    Cotacao cotar(String slug);
    Reserva reservar(UUID pedido, String slug);
    String confirmar(UUID pedido, UUID reserva);
    String liberar(UUID pedido, UUID reserva);

    record Cotacao(BigDecimal preco, String disponibilidade, UUID proprietarioId) { }
    record Reserva(UUID id, String estado, BigDecimal preco, UUID proprietarioId) { }

    enum Falha { CONFLITO, RESERVA_AUSENTE, RESPOSTA_INVALIDA, INDISPONIVEL }

    class ErroCatalogo extends RuntimeException {
        private final Falha falha;

        public ErroCatalogo(Falha falha, RuntimeException causa) {
            super(causa);
            this.falha = falha;
        }

        public Falha falha() { return falha; }

        public boolean conflito() {
            return falha == Falha.CONFLITO || falha == Falha.RESERVA_AUSENTE;
        }

        public String diagnostico() {
            return getCause().getClass().getSimpleName();
        }
    }
}
