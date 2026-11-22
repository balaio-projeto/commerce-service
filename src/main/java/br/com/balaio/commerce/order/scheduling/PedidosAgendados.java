package br.com.balaio.commerce.order.scheduling;

import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PedidosAgendados {
    private final ProcessarPedidosPendentesUseCase pedidos;
    private final boolean ativo;

    public PedidosAgendados(ProcessarPedidosPendentesUseCase pedidos,
            @Value("${balaio.worker.enabled:true}") boolean ativo) {
        this.pedidos = pedidos;
        this.ativo = ativo;
    }

    @Scheduled(fixedDelayString = "${balaio.worker.intervalo-ms:5000}")
    public void processar() {
        if (ativo) pedidos.processarPendentes();
    }
}
