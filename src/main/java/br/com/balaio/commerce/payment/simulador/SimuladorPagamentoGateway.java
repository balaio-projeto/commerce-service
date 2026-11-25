package br.com.balaio.commerce.payment.simulador;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;

/**
 * Provedor simulado de desenvolvimento. Guarda preferências, pagamentos e ordens em memória e
 * devolve no mesmo formato do Mercado Pago, para que a conciliação real (aplicarPagamento) seja usada.
 */
@Component
@Primary
@ConditionalOnProperty(name = "balaio.pagamento.simulador.ativo", havingValue = "true")
public class SimuladorPagamentoGateway implements PagamentoGateway {
    private static final Logger log = LoggerFactory.getLogger(SimuladorPagamentoGateway.class);

    private final Map<String, Preferencia> preferencias = new ConcurrentHashMap<>();
    private final Map<String, Pagamento> pagamentos = new ConcurrentHashMap<>();
    private final Map<String, Ordem> ordens = new ConcurrentHashMap<>();

    @Override
    public Preferencia criar(UUID pedido, List<Item> itens, BigDecimal frete) {
        var preferencia = new Preferencia("sim-pref-" + pedido, "http://localhost:4321/pagamento-simulado/" + pedido);
        preferencias.put(pedido.toString(), preferencia);
        return preferencia;
    }

    @Override
    public List<Preferencia> buscar(String referencia) {
        var preferencia = preferencias.get(referencia);
        return preferencia == null ? List.of() : List.of(preferencia);
    }

    @Override
    public Pagamento pagamento(String id) {
        var pagamento = pagamentos.get(id);
        if (pagamento == null) throw new IllegalStateException("Pagamento simulado inexistente.");
        return pagamento;
    }

    @Override
    public List<String> buscarPagamentos(String referencia) {
        return pagamentos.values().stream().filter(p -> p.referencia().equals(referencia)).map(Pagamento::id).toList();
    }

    @Override
    public Ordem ordem(String id) {
        var ordem = ordens.get(id);
        if (ordem == null) throw new IllegalStateException("Ordem simulada inexistente.");
        return ordem;
    }

    @Override
    public void cancelar(String paymentId) {
        log.info("Simulador: cancelamento de {} registrado", paymentId);
    }

    @Override
    public void reembolsar(String paymentId, UUID pedidoId) {
        log.info("Simulador: reembolso de {} do pedido {} registrado", paymentId, pedidoId);
    }

    /** Cria o pagamento aprovado da preferência do pedido, como o provedor faria ao concluir o checkout. */
    public String aprovar(UUID pedido, BigDecimal valor) {
        var preferencia = preferencias.get(pedido.toString());
        if (preferencia == null) throw new IllegalStateException("Pedido sem preferência simulada.");
        String ordemId = "sim-ord-" + UUID.randomUUID();
        String pagamentoId = "sim-pag-" + UUID.randomUUID();
        ordens.put(ordemId, new Ordem(preferencia.id(), pedido.toString()));
        pagamentos.put(pagamentoId, new Pagamento(pagamentoId, pedido.toString(), valor, "BRL", "approved",
                false, ordemId, BigDecimal.ZERO));
        return pagamentoId;
    }
}
