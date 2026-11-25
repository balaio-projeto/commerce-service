package br.com.balaio.commerce.notification.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase.SituacaoEmail;
import br.com.balaio.commerce.notification.domain.EnvioEmail;

public interface FilaEmailsPedido {
    List<EnvioEmail> reservarLote(int limite);
    void marcarEnviado(EnvioEmail envio);
    void registrarFalha(EnvioEmail envio, String erro, int maximoTentativas);
    List<SituacaoEmail> situacao(UUID pedido);
    int reagendarFalhas(UUID pedido);
    DadosPedido dadosPedido(UUID pedido);

    record DadosPedido(UUID comprador, UUID artesao, BigDecimal total, String rastreio, String transportadora,
            String motivo) { }
}
