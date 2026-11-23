package br.com.balaio.commerce.order.application.port.in;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ConsultarPedidosUseCase {
    Detalhe consultar(UUID id, UUID comprador);
    Resumo consultarArtesao(UUID id, UUID artesao);
    Resumo consultarInterno(UUID id);
    Participantes participantes(UUID id);
    List<Resumo> listar(UUID comprador);
    List<Resumo> listarVendasArtesao(UUID artesao);
    List<Pendente> listarPendentesArtesao(UUID artesao);

    record Detalhe(Resumo pedido, Object endereco) { }
    record Resumo(UUID id, UUID compradorId, UUID artesaoId, String pecaSlug, OffsetDateTime criadoEm,
            String estado, BigDecimal total, String motivoEncerramento, boolean historicoInferido,
            List<Etapa> etapas, List<ItemResumo> itens, Entrega entrega) { }
    record Entrega(BigDecimal frete, String rastreio, String transportadora, String freteOpcao,
            String freteProvedor, String previsaoEntrega) { }
    record Etapa(String estado, String titulo, String detalhe, String nota, OffsetDateTime criadaEm,
            boolean concluida, boolean atual, boolean inferida) { }
    record ItemResumo(String slug, int quantidade, BigDecimal preco) { }
    record Participantes(UUID compradorId, UUID artesaoId) { }
    record Pendente(UUID id, String pecaSlug, OffsetDateTime criadoEm, BigDecimal valor) { }
}
