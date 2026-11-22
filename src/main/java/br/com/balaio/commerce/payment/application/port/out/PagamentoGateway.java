package br.com.balaio.commerce.payment.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PagamentoGateway {
    Preferencia criar(UUID pedido, List<Item> itens, BigDecimal frete);
    List<Preferencia> buscar(String referencia);
    Pagamento pagamento(String id);
    List<String> buscarPagamentos(String referencia);
    Ordem ordem(String id);
    void cancelar(String paymentId);
    void reembolsar(String paymentId, UUID pedidoId);

    record Item(String slug, int quantidade, BigDecimal preco) { }
    record Preferencia(String id, String checkoutUrl) { }
    record Pagamento(String id, String referencia, BigDecimal valor, String moeda, String status,
            boolean liveMode, String ordemId, BigDecimal valorReembolsado) { }
    record Ordem(String preferenciaId, String referencia) { }

    class ProvedorIndisponivel extends RuntimeException {
        public ProvedorIndisponivel(RuntimeException causa) {
            super("Falha na comunicação com o provedor de pagamento.", causa);
        }

        public String diagnostico() {
            return getCause().getClass().getSimpleName();
        }
    }
}
