package br.com.balaio.commerce.order.application.port.in;

import java.util.UUID;

import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase.Resumo;

public interface ComandosPedidoUseCase {
    Resumo encerrar(UUID id, UUID ator, String tipo, String motivo);
    Resumo producao(UUID id, UUID artesao);
    Resumo avancar(UUID id, UUID artesao, String destino, String rastreio, String transportadora);
    Resumo mediacao(UUID id, UUID decisaoId, String decisao);
}
