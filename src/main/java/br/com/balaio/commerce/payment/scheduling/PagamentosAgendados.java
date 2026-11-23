package br.com.balaio.commerce.payment.scheduling;

import br.com.balaio.commerce.payment.application.port.in.ReconciliarPagamentosUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PagamentosAgendados {
    private final ReconciliarPagamentosUseCase pagamentos;
    private final boolean ativo;

    public PagamentosAgendados(ReconciliarPagamentosUseCase pagamentos,
            @Value("${payment.mercadopago.worker-enabled:true}") boolean ativo) {
        this.pagamentos = pagamentos;
        this.ativo = ativo;
    }

    @Scheduled(fixedDelayString = "${payment.mercadopago.worker-interval-ms:10000}")
    public void reconciliar() {
        if (ativo) pagamentos.reconciliar();
    }
}
